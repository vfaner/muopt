package com.sqloptimizer.service;

import com.sqloptimizer.entity.DatabaseConfig;
import com.sqloptimizer.repository.DatabaseConfigRepository;
import com.sqloptimizer.service.connection.DriverLoader;
import com.sqloptimizer.util.CryptoUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 数据源服务：管理多条数据库连接配置，但只持有“当前启用”那一条的连接池。
 * 提供：激活/停用、连接测试、索引存在性检测、表行数统计、冗余索引检测。
 *
 * <p>互斥启用标志由 {@link DatabaseConfigService} 在事务内维护；本服务负责连接池的
 * 验证与热切换——新池验证通过后才替换旧池，验证失败时原连接原样保留。
 */
@Slf4j
@Service
public class DataSourceService {

    private final DatabaseConfigRepository repository;
    private final DatabaseConfigService configService;
    private final CryptoUtil cryptoUtil;
    private final DriverLoader driverLoader;

    private volatile HikariDataSource dataSource;
    private volatile DatabaseConfig currentConfig;

    /** COUNT(*) 统计的查询超时（秒），避免大表把用户的库拖死 */
    private static final int COUNT_TIMEOUT_SECONDS = 5;

    public DataSourceService(DatabaseConfigRepository repository,
                             DatabaseConfigService configService,
                             CryptoUtil cryptoUtil,
                             DriverLoader driverLoader) {
        this.repository = repository;
        this.configService = configService;
        this.cryptoUtil = cryptoUtil;
        this.driverLoader = driverLoader;
    }

    /**
     * 应用启动后尽力恢复上次启用的连接。失败只记录日志：启用标志仍在，
     * 页面显示“启用中·连接失败”，用户可重新点击启用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconnectOnStartup() {
        repository.findFirstByEnabledTrue().ifPresent(config -> {
            try {
                doActivate(config);
                log.info("启动恢复数据源连接成功: {}({})", config.getName(), config.getDbType());
            } catch (Exception e) {
                log.warn("启动恢复数据源连接失败 '{}': {}", config.getName(), rootMessage(e));
            }
        });
    }

    /**
     * 是否已连接数据源
     */
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed() && currentConfig != null;
    }

    public DatabaseConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * 激活某条配置：先建新池并验证，成功后事务内切换启用标志，再热替换旧池。
     * 任何一步失败都不会影响当前在用连接。
     */
    public synchronized DatabaseConfig activate(Long id) {
        DatabaseConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源配置不存在"));
        doActivate(config);
        return currentConfig;
    }

    private void doActivate(DatabaseConfig config) {
        String rawPassword;
        try {
            rawPassword = cryptoUtil.decrypt(config.getPassword());
        } catch (IllegalStateException e) {
            throw new IllegalStateException("密码无法解密，请编辑该数据源重新填写密码后再启用");
        }

        // 1. 建新池并主动取连接验证；失败时关掉新池，旧池不动
        HikariDataSource newDs = createAndValidatePool(config, rawPassword);

        // 2. 事务内互斥切换启用标志（此时新池已可用）
        DatabaseConfig enabled;
        try {
            enabled = configService.enableExclusive(config.getId());
        } catch (RuntimeException e) {
            closeQuietly(newDs);
            throw e;
        }

        // 3. 替换并关闭旧池
        HikariDataSource old = this.dataSource;
        this.dataSource = newDs;
        this.currentConfig = enabled;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
        log.info("数据源激活成功: {} {}:{}/{}", enabled.getDbType(),
                enabled.getHost(), enabled.getPort(), enabled.getDatabaseName());
    }

    /**
     * 停用当前（或指定）配置：清启用标志并关闭连接池。
     */
    public synchronized void deactivate(Long id) {
        configService.disable(id);
        if (currentConfig != null && currentConfig.getId().equals(id)) {
            closeQuietly();
            currentConfig = null;
        }
    }

    /**
     * 测试连接。已保存配置密码留空时使用库中密码；未保存表单需提供明文密码。
     * 失败以结果返回而非抛出，UI 直接展示原因。
     */
    public TestResult testConnection(DatabaseConfig input, String rawPassword) {
        String password = rawPassword;
        if ((password == null || password.isEmpty()) && input.getId() != null) {
            DatabaseConfig stored = repository.findById(input.getId()).orElse(null);
            if (stored != null) {
                password = cryptoUtil.decrypt(stored.getPassword());
            }
        }
        // 自定义类型允许免密库（如内嵌库）；预设类型必须有密码
        if ((password == null || password.isEmpty()) && !"custom".equalsIgnoreCase(input.getDbType())) {
            return TestResult.failure("密码不能为空");
        }
        HikariDataSource ds = null;
        long start = System.currentTimeMillis();
        try {
            ds = createAndValidatePool(input, password);
            String productInfo;
            try (Connection conn = ds.getConnection()) {
                productInfo = conn.getMetaData().getDatabaseProductName();
            }
            return TestResult.success("连接成功（" + productInfo + "）", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("数据源连接测试失败: {}", e.getMessage());
            return TestResult.failure("连接失败: " + rootMessage(e));
        } finally {
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 保存活动配置后刷新连接池：用新设置重新激活，失败时旧连接保留
     * （启用标志仍为 true，页面显示连接失败，可再次尝试）。
     */
    public synchronized void refreshIfActive(Long id) {
        if (currentConfig != null && currentConfig.getId().equals(id)) {
            doActivate(repository.findById(id).orElseThrow());
        }
    }

    // ---------- 依赖业务库连接的分析能力（逻辑保持不变） ----------

    /**
     * 检测索引建议在数据源中是否已存在，填充 status 字段。
     * 已存在 -> EXISTS（灰色）；不存在 -> MISSING（橙色）
     */
    public void detectIndexExistence(List<com.sqloptimizer.common.IndexSuggestion> suggestions) {
        if (!isConnected() || suggestions == null || suggestions.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (com.sqloptimizer.common.IndexSuggestion s : suggestions) {
                try {
                    Map<String, IndexMeta> indexes = loadIndexes(meta, conn, s.getTableName());
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
     * 加载表上所有索引：索引名 -> 索引元信息（有序列名 + 是否唯一）
     */
    private Map<String, IndexMeta> loadIndexes(DatabaseMetaData meta, Connection conn, String table) throws SQLException {
        Map<String, IndexMeta> indexMap = new LinkedHashMap<>();
        // 尝试原始表名与大写表名（信创库/Oracle系多为大写）
        for (String t : distinctNames(table)) {
            try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), conn.getSchema(), t, false, false)) {
                while (rs.next()) {
                    String idxName = rs.getString("INDEX_NAME");
                    String colName = rs.getString("COLUMN_NAME");
                    if (idxName == null || colName == null) {
                        continue;
                    }
                    boolean unique = !rs.getBoolean("NON_UNIQUE");
                    indexMap.computeIfAbsent(idxName, k -> new IndexMeta(k, unique))
                            .columns.add(colName.toLowerCase());
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
    private String matchIndex(Map<String, IndexMeta> indexes, List<String> wantColumns) {
        List<String> want = wantColumns.stream().map(String::toLowerCase).toList();
        for (Map.Entry<String, IndexMeta> e : indexes.entrySet()) {
            List<String> idxCols = e.getValue().columns;
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
     * 只建议删除「普通二级索引」——主键与唯一索引承载约束语义，被前缀覆盖也不能删。
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
                Map<String, IndexMeta> indexes = loadIndexes(meta, conn, table);
                List<IndexMeta> entries = new ArrayList<>(indexes.values());
                for (IndexMeta shorter : entries) {
                    if (!isDroppable(shorter)) {
                        continue;
                    }
                    for (IndexMeta longer : entries) {
                        if (shorter == longer) {
                            continue;
                        }
                        if (shorter.columns.size() < longer.columns.size()
                                && isPrefix(shorter.columns, longer.columns)) {
                            String dropStmt = buildDropIndex(shorter.name, table);
                            String sql = dropStmt + " -- 冗余：已被索引 "
                                    + longer.name + " (" + String.join(", ", longer.columns) + ") 覆盖";
                            if (drops.stream().noneMatch(d -> d.startsWith(dropStmt))) {
                                drops.add(sql);
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("检测冗余索引失败: {}", e.getMessage());
        }
        return drops;
    }

    /** DROP INDEX 语法按方言区分：MySQL 系需要 ON 表名，PG/Oracle 系不需要 */
    private String buildDropIndex(String indexName, String table) {
        String type = currentConfig != null && currentConfig.getDbType() != null
                ? currentConfig.getDbType().toLowerCase() : "";
        return switch (type) {
            case "mysql", "oceanbase", "tidb" -> "DROP INDEX " + indexName + " ON " + table + ";";
            default -> "DROP INDEX " + indexName + ";";
        };
    }

    /**
     * 是否可以建议删除：主键与唯一索引不可删（承载唯一性约束/聚簇职责）。
     */
    private boolean isDroppable(IndexMeta idx) {
        if (idx.unique) {
            return false;
        }
        String n = idx.name == null ? "" : idx.name.toUpperCase();
        return !n.equals("PRIMARY") && !n.equals("PK") && !n.startsWith("PRIMARY_") && !n.startsWith("SYS_");
    }

    /** 表上单个索引的元信息 */
    private static class IndexMeta {
        final String name;
        final boolean unique;
        final List<String> columns = new ArrayList<>();

        IndexMeta(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
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
     * 加查询超时，避免在大表上把用户的库拖死。
     */
    public long countTableRows(String table) {
        if (!isConnected()) {
            return -1;
        }
        for (String t : distinctNames(table)) {
            String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(t);
            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement()) {
                st.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
                try (ResultSet rs = st.executeQuery(sql)) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            } catch (Exception e) {
                log.debug("统计表 {} 行数失败: {}", t, e.getMessage());
            }
        }
        return -1;
    }

    /**
     * 表名只允许普通标识符字符（含反引号/双引号/方括号引用形式，如 MySQL 保留字 {@code `class`}），
     * 防止解析出的表名把额外 SQL 片段带进拼接语句。
     */
    private String quoteIdentifier(String table) {
        if (table == null || !table.matches("[A-Za-z0-9_$.`\"\\[\\]]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return table;
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

    // ---------- 连接池构建 ----------

    /**
     * 按配置创建连接池并主动取一条连接验证，失败关闭新池后抛出。
     */
    private HikariDataSource createAndValidatePool(DatabaseConfig config, String rawPassword) {
        String driverClass = resolveDriverClass(config);
        String jdbcUrl = buildJdbcUrl(config);
        // 自定义 jar 驱动：先加载并注册 DriverShim，再交给 DriverManager 发现
        boolean externalJar = StringUtils.hasText(config.getCustomJarPath());
        ClassLoader driverLoader = this.driverLoader.ensureDriverLoaded(driverClass, config.getCustomJarPath());

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(config.getUsername());
        hc.setPassword(rawPassword);
        if (!externalJar) {
            // 外部 jar 的驱动类不在应用类加载器上，Hikari 直接 Class.forName 会失败；
            // 此时靠已注册的 DriverShim 经 DriverManager.getDriver(url) 发现
            hc.setDriverClassName(driverClass);
        }
        hc.setMaximumPoolSize(3);
        hc.setConnectionTimeout(8000);
        hc.setInitializationFailTimeout(8000);
        hc.setPoolName("sql-optimizer-ds-" + (config.getId() == null ? "tmp" : config.getId()));

        HikariDataSource ds = null;
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 部分驱动（如达梦）初始化/连接时依赖上下文类加载器
            Thread.currentThread().setContextClassLoader(driverLoader);
            // 构造器在 initializationFailTimeout>0 时自身即可能抛 PoolInitializationException
            ds = new HikariDataSource(hc);
            try (Connection conn = ds.getConnection()) {
                conn.getMetaData().getDatabaseProductName();
            }
            return ds;
        } catch (Exception e) {
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception ignored) {
                }
            }
            log.error("数据源连接失败", e);
            // 业务状态类错误（凭据错误/网络不通），交由全局异常处理返回 400 与原始原因
            throw new IllegalStateException("数据源连接失败: " + rootMessage(e));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    // ---------- 工具方法 ----------

    private List<String> distinctNames(String table) {
        String t = table == null ? "" : table.trim();
        String bare = stripQuotes(t);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        // 同时尝试带引号（`class`、"class"、[class]）与去引号形式，
        // JDBC 元数据接口通常需要不带引号的真实表名才能命中
        set.add(t);
        set.add(bare);
        set.add(t.toUpperCase());
        set.add(t.toLowerCase());
        set.add(bare.toUpperCase());
        set.add(bare.toLowerCase());
        return new ArrayList<>(set);
    }

    /** 去掉标识符最外层的 MySQL 反引号 / 双引号 / SQL Server 方括号 */
    private String stripQuotes(String name) {
        String s = name.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private String resolveDriverClass(DatabaseConfig c) {
        String dbType = c.getDbType();
        if (dbType == null) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        if ("custom".equalsIgnoreCase(dbType)) {
            return c.getCustomDriver().trim();
        }
        return switch (dbType.toLowerCase()) {
            case "mysql", "oceanbase", "tidb" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql", "gaussdb", "opengauss", "kingbase" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "dameng", "dm" -> "dm.jdbc.driver.DmDriver";
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    private String buildJdbcUrl(DatabaseConfig c) {
        String type = c.getDbType().toLowerCase();
        String url = switch (type) {
            case "custom" -> c.getCustomUrl().trim();
            case "mysql", "oceanbase", "tidb" ->
                    "jdbc:mysql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName()
                            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
            case "postgresql", "gaussdb", "opengauss", "kingbase" ->
                    "jdbc:postgresql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
            case "oracle" ->
                    "jdbc:oracle:thin:@//" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
            case "dameng", "dm" ->
                    "jdbc:dm://" + c.getHost() + ":" + c.getPort()
                            + (c.getDatabaseName() == null || c.getDatabaseName().isBlank()
                            ? "" : "?schema=" + c.getDatabaseName());
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + c.getDbType());
        };
        return appendExtraParams(url, c.getExtraParams());
    }

    /** 追加 k=v&k=v 形式的附加参数；SQL Server/DB2 风格（分号分隔）自动适配分隔符 */
    private String appendExtraParams(String url, String extra) {
        if (!StringUtils.hasText(extra)) {
            return url;
        }
        String trimmed = extra.trim();
        while (trimmed.startsWith("?") || trimmed.startsWith("&") || trimmed.startsWith(";")) {
            trimmed = trimmed.substring(1);
        }
        char separator = url.contains(";") && !url.contains("?") ? ';'
                : (url.contains("?") ? '&' : '?');
        return url + separator + trimmed;
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

    private void closeQuietly(HikariDataSource ds) {
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    /** 连接测试结果 */
    public static class TestResult {
        private final boolean success;
        private final String message;
        private final long elapsedMs;

        private TestResult(boolean success, String message, long elapsedMs) {
            this.success = success;
            this.message = message;
            this.elapsedMs = elapsedMs;
        }

        public static TestResult success(String message, long elapsedMs) {
            return new TestResult(true, message, elapsedMs);
        }

        public static TestResult failure(String message) {
            return new TestResult(false, message, 0);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }
    }
}
