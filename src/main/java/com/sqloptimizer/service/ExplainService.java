package com.sqloptimizer.service;

import com.sqloptimizer.common.ExplainResult;
import com.sqloptimizer.common.ExplainRow;
import com.sqloptimizer.common.IndexSuggestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.List;

/**
 * SQL 执行计划分析服务（需数据源）
 * 针对不同数据库执行 EXPLAIN，解析结果并做成本评估，必要时给出索引建议。
 */
@Slf4j
@Service
public class ExplainService {

    private final DataSourceService dataSourceService;
    private final IndexAnalyzerService indexAnalyzer;

    /** 成本阈值：超过则认为偏高，建议加索引 */
    private static final double HIGH_COST_THRESHOLD = 10000d;
    private static final double MEDIUM_COST_THRESHOLD = 1000d;
    /** 扫描行数很少时，无需建索引 */
    private static final long SMALL_ROWS_THRESHOLD = 500L;

    @Autowired
    public ExplainService(DataSourceService dataSourceService, IndexAnalyzerService indexAnalyzer) {
        this.dataSourceService = dataSourceService;
        this.indexAnalyzer = indexAnalyzer;
    }

    /**
     * 执行 EXPLAIN 并分析
     */
    public ExplainResult explain(String sql) {
        if (!dataSourceService.isConnected()) {
            throw new IllegalStateException("未配置数据源，请先在「配置数据源」菜单中配置并连接数据库");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        String dbType = dataSourceService.getCurrentConfig().getDbType().toLowerCase();
        String explainSql = buildExplainSql(dbType, sql.trim());

        ExplainResult result = new ExplainResult();
        try (Connection conn = dataSourceService.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(explainSql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                result.getHeaders().add(meta.getColumnLabel(i));
            }

            StringBuilder rawPlan = new StringBuilder();
            while (rs.next()) {
                ExplainRow row = new ExplainRow();
                for (int i = 1; i <= colCount; i++) {
                    Object val = rs.getObject(i);
                    row.put(meta.getColumnLabel(i), val);
                    rawPlan.append(val).append("\t");
                }
                rawPlan.append("\n");
                result.getRows().add(row);
            }
            result.setRawPlan(rawPlan.toString().trim());

            // 解析成本与行数
            parseCostAndRows(dbType, result);
            evaluate(result, sql.trim());
        } catch (Exception e) {
            log.error("执行计划分析失败", e);
            throw new RuntimeException("执行计划分析失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 不同数据库的 EXPLAIN 语法
     */
    private String buildExplainSql(String dbType, String sql) {
        return switch (dbType) {
            case "mysql", "oceanbase", "tidb" -> "EXPLAIN " + sql;
            // PostgreSQL / GaussDB / KingBase 使用 EXPLAIN（不加 ANALYZE 避免真实执行写操作）
            case "postgresql", "gaussdb", "opengauss", "kingbase" -> "EXPLAIN " + sql;
            case "dameng", "dm" -> "EXPLAIN " + sql;
            default -> "EXPLAIN " + sql;
        };
    }

    /**
     * 从执行计划中提取成本与预估行数
     */
    private void parseCostAndRows(String dbType, ExplainResult result) {
        // MySQL 系：rows 列
        if (dbType.equals("mysql") || dbType.equals("oceanbase") || dbType.equals("tidb")) {
            long maxRows = 0;
            for (ExplainRow row : result.getRows()) {
                Object rowsVal = findValueIgnoreCase(row, "rows");
                if (rowsVal != null) {
                    try {
                        maxRows = Math.max(maxRows, Long.parseLong(rowsVal.toString().trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (maxRows > 0) {
                result.setEstimatedRows(maxRows);
                // MySQL 无直接成本，用扫描行数近似
                result.setTotalCost((double) maxRows);
            }
            return;
        }

        // PostgreSQL / GaussDB 系：计划文本形如  cost=0.00..12345.67 rows=1000
        for (ExplainRow row : result.getRows()) {
            for (Object val : row.getColumns().values()) {
                if (val == null) continue;
                String line = val.toString();
                Double cost = extractPgCost(line);
                if (cost != null && (result.getTotalCost() == null || cost > result.getTotalCost())) {
                    result.setTotalCost(cost);
                }
                Long rows = extractPgRows(line);
                if (rows != null && (result.getEstimatedRows() == null || rows > result.getEstimatedRows())) {
                    result.setEstimatedRows(rows);
                }
            }
        }
    }

    private Double extractPgCost(String line) {
        // 匹配 cost=0.00..12345.67，取上界
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("cost=[0-9.]+\\.\\.([0-9.]+)").matcher(line);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Long extractPgRows(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("rows=([0-9]+)").matcher(line);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Object findValueIgnoreCase(ExplainRow row, String key) {
        for (var e : row.getColumns().entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 成本评估 + 给出建议
     */
    private void evaluate(ExplainResult result, String originalSql) {
        Double cost = result.getTotalCost();
        Long rows = result.getEstimatedRows();

        // 检测全表扫描关键字
        boolean fullScan = result.getRawPlan() != null
                && (result.getRawPlan().toUpperCase().contains("ALL")
                || result.getRawPlan().toUpperCase().contains("SEQ SCAN")
                || result.getRawPlan().toUpperCase().contains("FULL"));

        if (cost == null) {
            result.setCostLevel("UNKNOWN");
            result.getTips().add("无法从执行计划中解析出成本值，请结合上方计划明细人工评估。");
            return;
        }

        if (rows != null && rows <= SMALL_ROWS_THRESHOLD) {
            result.setCostLevel("LOW");
            result.getTips().add(String.format(
                    "预估扫描行数很少（约 %d 行），即使存在全表扫描代价也很低，无需额外建立索引。", rows));
            return;
        }

        if (cost >= HIGH_COST_THRESHOLD) {
            result.setCostLevel("HIGH");
            result.getTips().add(String.format("执行计划成本偏高（约 %.2f），建议优化。", cost));
            if (fullScan) {
                result.getTips().add("检测到全表扫描，以下为基于 SQL 结构给出的索引建议：");
            }
            // 高成本才给索引建议
            List<IndexSuggestion> suggestions = indexAnalyzer.analyze(originalSql);
            dataSourceService.detectIndexExistence(suggestions);
            for (IndexSuggestion s : suggestions) {
                fillCreateSql(s);
            }
            result.setIndexSuggestions(suggestions);
        } else if (cost >= MEDIUM_COST_THRESHOLD) {
            result.setCostLevel("MEDIUM");
            result.getTips().add(String.format("执行计划成本中等（约 %.2f），可关注是否有优化空间。", cost));
        } else {
            result.setCostLevel("LOW");
            result.getTips().add(String.format("执行计划成本较低（约 %.2f），当前查询效率良好。", cost));
        }
    }

    private void fillCreateSql(IndexSuggestion s) {
        String idxName = "idx_" + s.getTableName().toLowerCase() + "_"
                + String.join("_", s.getColumns()).toLowerCase();
        if (idxName.length() > 60) {
            idxName = idxName.substring(0, 60);
        }
        s.setIndexName(idxName);
        s.setCreateSql("CREATE INDEX " + idxName + " ON " + s.getTableName()
                + " (" + String.join(", ", s.getColumns()) + ");");
    }
}
