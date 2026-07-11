package com.sqloptimizer.service;

import com.sqloptimizer.common.DataSourceConfig;
import com.sqloptimizer.common.IndexSuggestion;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 数据源服务（非必需功能，内存态单例持有当前连接）
 * 提供：连接测试、索引存在性检测、表行数统计（用于大小表驱动判断）、冗余索引检测。
 */
@Slf4j
@Service
public class DataSourceService {

    private volatile HikariDataSource dataSource;
    private volatile DataSourceConfig currentConfig;

    /**
     * 是否已连接数据源
     */
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed() && currentConfig != null && currentConfig.isConnected();
    }

    public DataSourceConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * 配置并测试连接。成功则持有连接池，失败抛异常。
     */
    public synchronized void connect(DataSourceConfig config) {
        closeQuietly();

        String driverClass = resolveDriver(config.getDbType());
        String jdbcUrl = buildJdbcUrl(config);

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());
        hc.setDriverClassName(driverClass);
        hc.setMaximumPoolSize(3);
        hc.setConnectionTimeout(8000);
        hc.setInitializationFailTimeout(8000);
        hc.setPoolName("sql-optimizer-ds");

        try {
            HikariDataSource ds = new HikariDataSource(hc);
            // 主动取一个连接验证
            try (Connection conn = ds.getConnection()) {
                conn.getMetaData().getDatabaseProductName();
            }
            this.dataSource = ds;
            config.setConnected(true);
            config.setPassword(null); // 不回传密码
            this.currentConfig = config;
            log.info("数据源连接成功: {} {}:{}/{}", config.getDbType(), config.getHost(), config.getPort(), config.getDatabase());
        } catch (Exception e) {
            log.error("数据源连接失败", e);
            throw new RuntimeException("数据源连接失败: " + rootMessage(e));
        }
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        closeQuietly();
        this.currentConfig = null;
    }

    /**
     * 检测索引建议在数据源中是否已存在，填充 status 字段。
     * 已存在 -> EXISTS（灰色）；不存在 -> MISSING（橙色）
     */
    public void detectIndexExistence(List<IndexSuggestion> suggestions) {
        if (!isConnected() || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (IndexSuggestion s : suggestions) {
                try {
                    Map<String, List<String>> indexes = loadIndexes(meta, conn, s.getTableName());
                    String matched = matchIndex(indexes, s.getColumns());
                    if (matched != null) {
                        s.setStatus("EXISTS");
                        s.setExistingIndexName(matched);
                    } else {
                        s.setStatus("MISSING");
                    }
                } catch (Exception e) {
                    log.warn("检测索引存在性失败 table={}: {}", s.getTableName(), e.getMessage());
                    s.setStatus("MISSING");
                }
            }
        } catch (Exception e) {
            log.warn("获取数据库元数据失败: {}", e.getMessage());
        }
    }

    /**
     * 加载表上所有索引：索引名 -> 有序列名列表
     */
    private Map<String, List<String>> loadIndexes(DatabaseMetaData meta, Connection conn, String table) throws SQLException {
        Map<String, List<String>> indexMap = new LinkedHashMap<>();
        // 尝试原始表名与大写表名（信创库/Oracle系多为大写）
        for (String t : distinctNames(table)) {
            try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), conn.getSchema(), t, false, false)) {
                while (rs.next()) {
                    String idxName = rs.getString("INDEX_NAME");
                    String colName = rs.getString("COLUMN_NAME");
                    if (idxName == null || colName == null) {
                        continue;
                    }
                    indexMap.computeIfAbsent(idxName, k -> new ArrayList<>()).add(colName.toLowerCase());
                }
            }
            if (!indexMap.isEmpty()) {
                break;
            }
        }
        return indexMap;
    }

    /**
     * 判断候选列组合是否已被某个索引覆盖（作为最左前缀即可）
     */
    private String matchIndex(Map<String, List<String>> indexes, List<String> wantColumns) {
        List<String> want = wantColumns.stream().map(String::toLowerCase).toList();
        for (Map.Entry<String, List<String>> e : indexes.entrySet()) {
            List<String> idxCols = e.getValue();
            if (idxCols.size() < want.size()) {
                continue;
            }
            boolean prefixMatch = true;
            for (int i = 0; i < want.size(); i++) {
                if (!idxCols.get(i).equals(want.get(i))) {
                    prefixMatch = false;
                    break;
                }
            }
            if (prefixMatch) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 检测冗余索引：某索引的列是另一个索引列的最左前缀，则前者冗余。
     * 返回建议删除的 DROP 语句列表。
     */
    public List<String> detectRedundantIndexes(Collection<String> tables) {
        List<String> drops = new ArrayList<>();
        if (!isConnected() || tables == null || tables.isEmpty()) {
            return drops;
        }
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String table : new LinkedHashSet<>(tables)) {
                Map<String, List<String>> indexes = loadIndexes(meta, conn, table);
                List<Map.Entry<String, List<String>>> entries = new ArrayList<>(indexes.entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    for (int j = 0; j < entries.size(); j++) {
                        if (i == j) continue;
                        List<String> shorter = entries.get(i).getValue();
                        List<String> longer = entries.get(j).getValue();
                        if (shorter.size() < longer.size() && isPrefix(shorter, longer)) {
                            String dropName = entries.get(i).getKey();
                            String sql = "DROP INDEX " + dropName + "; -- 冗余：已被索引 "
                                    + entries.get(j).getKey() + " (" + String.join(", ", longer) + ") 覆盖";
                            if (drops.stream().noneMatch(d -> d.startsWith("DROP INDEX " + dropName + ";"))) {
                                drops.add(sql);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("检测冗余索引失败: {}", e.getMessage());
        }
        return drops;
    }

    private boolean isPrefix(List<String> prefix, List<String> full) {
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(full.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 统计表的估算行数（用于大小表驱动判断）。失败返回 -1。
     */
    public long countTableRows(String table) {
        if (!isConnected()) {
            return -1;
        }
        for (String t : distinctNames(table)) {
            String sql = "SELECT COUNT(*) FROM " + t;
            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } catch (Exception e) {
                log.debug("统计表 {} 行数失败: {}", t, e.getMessage());
            }
        }
        return -1;
    }

    /**
     * 获取一个连接（供 ExplainService 使用）
     */
    public Connection getConnection() throws SQLException {
        if (!isConnected()) {
            throw new IllegalStateException("未配置数据源");
        }
        return dataSource.getConnection();
    }

    // ---------- 工具方法 ----------

    private List<String> distinctNames(String table) {
        String t = table == null ? "" : table.trim();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(t);
        set.add(t.toUpperCase());
        set.add(t.toLowerCase());
        return new ArrayList<>(set);
    }

    private String resolveDriver(String dbType) {
        if (dbType == null) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        return switch (dbType.toLowerCase()) {
            case "mysql", "oceanbase", "tidb" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql", "gaussdb", "opengauss", "kingbase" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "dameng", "dm" -> "dm.jdbc.driver.DmDriver";
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    private String buildJdbcUrl(DataSourceConfig c) {
        String type = c.getDbType().toLowerCase();
        return switch (type) {
            case "mysql", "oceanbase", "tidb" ->
                    "jdbc:mysql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabase()
                            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
            case "postgresql", "gaussdb", "opengauss", "kingbase" ->
                    "jdbc:postgresql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabase();
            case "oracle" ->
                    "jdbc:oracle:thin:@//" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabase();
            case "dameng", "dm" ->
                    "jdbc:dm://" + c.getHost() + ":" + c.getPort();
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + c.getDbType());
        };
    }

    private void closeQuietly() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception ignored) {
            }
            dataSource = null;
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }
}
