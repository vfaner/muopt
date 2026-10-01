package com.qqmu.muopt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.muopt.common.ExplainResult;
import com.qqmu.muopt.common.ExplainRow;
import com.qqmu.muopt.common.IndexSuggestion;
import com.qqmu.muopt.common.PlanNode;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 成本阈值：超过则认为偏高，建议加索引 */
    private static final double HIGH_COST_THRESHOLD = 10000d;
    private static final double MEDIUM_COST_THRESHOLD = 1000d;
    /** 扫描行数很少时，无需建索引 */
    private static final long SMALL_ROWS_THRESHOLD = 500L;

    /** 文本执行计划中的全表扫描特征（词边界匹配，避免误伤 full_name / t_call_log 这类标识符） */
    private static final java.util.regex.Pattern FULL_SCAN_TEXT = java.util.regex.Pattern.compile(
            "\\bSEQ\\s+SCAN\\b|\\bFULL\\s+(TABLE\\s+)?SCAN\\b|\\bTABLE\\s+ACCESS\\s+FULL\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

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
        String trimmedSql = sql.trim();

        ExplainResult result = new ExplainResult();
        result.setDbType(dbType);
        try (Connection conn = dataSourceService.getConnection()) {

            if (isOracle(dbType)) {
                // Oracle 不支持 EXPLAIN <sql>，必须先 EXPLAIN PLAN FOR 再从 DBMS_XPLAN 读回
                runOracleExplain(conn, trimmedSql, result);
            } else {
                fillFromQuery(conn, buildExplainSql(dbType, trimmedSql), result);
            }

            // 解析成本与行数
            parseCostAndRows(dbType, result);

            // MySQL 系：用 EXPLAIN FORMAT=JSON 获取真实成本(query_cost)与结构化执行计划树
            if (isMysqlFamily(dbType)) {
                enrichWithJsonPlan(conn, trimmedSql, result);
            }

            evaluate(result, trimmedSql);
        } catch (Exception e) {
            log.error("执行计划分析失败", e);
            throw new RuntimeException("执行计划分析失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 执行一条返回结果集的计划查询，填充表头、数据行与原始计划文本。
     */
    private void fillFromQuery(Connection conn, String query, ExplainResult result) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {

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
        }
    }

    /**
     * Oracle 执行计划：EXPLAIN PLAN FOR 写入 PLAN_TABLE，再用 DBMS_XPLAN.DISPLAY 读回文本计划。
     * 需要当前用户可访问 PLAN_TABLE（Oracle 10g 起为内置全局临时表 PLAN_TABLE$）。
     */
    private void runOracleExplain(Connection conn, String sql, ExplainResult result) throws SQLException {
        String stmtId = "sqlopt_" + System.nanoTime();
        try (Statement st = conn.createStatement()) {
            st.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + stmtId + "' FOR " + sql);
        } catch (SQLException e) {
            if (e.getMessage() != null && (e.getMessage().contains("ORA-00942") || e.getMessage().contains("ORA-02402"))) {
                throw new SQLException("Oracle 执行计划需要 PLAN_TABLE，当前用户不可访问。"
                        + "请执行 @?/rdbms/admin/utlxplan.sql 创建，或授予 PLAN_TABLE 权限。原始错误: " + e.getMessage(), e);
            }
            throw e;
        }
        fillFromQuery(conn,
                "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', '" + stmtId + "', 'TYPICAL'))",
                result);
    }

    private boolean isOracle(String dbType) {
        return "oracle".equals(dbType);
    }

    /**
     * 不同数据库的 EXPLAIN 语法（Oracle 走 runOracleExplain，不经过这里）
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

    private boolean isMysqlFamily(String dbType) {
        return dbType.equals("mysql") || dbType.equals("oceanbase") || dbType.equals("tidb");
    }

    /**
     * MySQL 系专用：执行 EXPLAIN FORMAT=JSON，解析真实成本与结构化执行计划树。
     * 兼容 MySQL 8.0.16+ 的新 TREE 格式（query_plan + inputs）。失败时静默降级（仍有表格视图）。
     */
    private void enrichWithJsonPlan(Connection conn, String sql, ExplainResult result) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
            if (!rs.next()) {
                return;
            }
            String json = rs.getString(1);
            if (json == null || json.isBlank()) {
                return;
            }
            result.setRawJson(json);
            JsonNode root = objectMapper.readTree(json);

            // 新格式（MySQL 8.0.16+）：{ "query_plan": { ... inputs ... } }
            if (root.has("query_plan")) {
                JsonNode top = root.get("query_plan");
                PlanNode rootNode = parseTreeNode(top);
                if (rootNode != null) {
                    result.getPlanTree().add(rootNode);
                    // 根节点的 estimated_total_cost 即整棵计划的总成本
                    if (top.hasNonNull("estimated_total_cost")) {
                        result.setTotalCost(top.get("estimated_total_cost").asDouble());
                    }
                    // 根节点的 estimated_rows 是「输出」行数（LIMIT 后），不能当扫描行数用，
                    // 否则 SELECT * FROM 大表 LIMIT 10 会被误判为"扫描行数很少、无需索引"。
                    // 表格视图里的 rows 列才是每步扫描行数，已解析则不覆盖。
                    if (result.getEstimatedRows() == null && top.hasNonNull("estimated_rows")) {
                        result.setEstimatedRows((long) top.get("estimated_rows").asDouble());
                    }
                }
                return;
            }

            // 旧格式：{ "query_block": { ... nested_loop ... } }
            JsonNode queryBlock = root.get("query_block");
            if (queryBlock != null) {
                Double cost = extractQueryCost(queryBlock);
                if (cost != null) {
                    result.setTotalCost(cost);
                }
                PlanNode rootNode = parseQueryBlock(queryBlock);
                if (rootNode != null) {
                    result.getPlanTree().add(rootNode);
                }
            }
        } catch (Exception e) {
            log.debug("EXPLAIN FORMAT=JSON 解析失败（降级为表格视图）: {}", e.getMessage());
        }
    }

    /**
     * 递归解析 MySQL 8 新 TREE 格式节点。
     * 每个节点有 operation / access_type / estimated_rows / estimated_total_cost，
     * 子节点在 inputs 数组中。
     */
    private PlanNode parseTreeNode(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return null;
        }
        PlanNode node = new PlanNode();
        node.setOperation(n.path("operation").asText(""));
        node.setAccessType(n.path("access_type").asText(""));
        if (n.hasNonNull("table_name")) {
            node.setTableName(n.get("table_name").asText());
            node.setDetail(n.get("table_name").asText());
        }
        if (n.hasNonNull("index_name")) {
            node.setKey(n.get("index_name").asText());
        }
        if (n.hasNonNull("estimated_rows")) {
            node.setRows((long) n.get("estimated_rows").asDouble());
        }
        if (n.hasNonNull("estimated_total_cost")) {
            node.setCost(String.valueOf(n.get("estimated_total_cost").asDouble()));
        }
        // 递归子节点
        JsonNode inputs = n.get("inputs");
        if (inputs != null && inputs.isArray()) {
            for (JsonNode child : inputs) {
                PlanNode c = parseTreeNode(child);
                if (c != null) {
                    node.getChildren().add(c);
                }
            }
        }
        return node;
    }

    private Double extractQueryCost(JsonNode queryBlock) {
        JsonNode costInfo = queryBlock.get("cost_info");
        if (costInfo != null && costInfo.hasNonNull("query_cost")) {
            try {
                return Double.parseDouble(costInfo.get("query_cost").asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * 递归解析 MySQL 旧 JSON 执行计划（8.0.16 之前）。结构大致为：
     * query_block -> (ordering_operation|grouping_operation)* -> nested_loop[] | table
     */
    private PlanNode parseQueryBlock(JsonNode queryBlock) {
        // 排序 / 分组包裹节点
        if (queryBlock.has("ordering_operation")) {
            PlanNode node = new PlanNode();
            node.setOperation("Order By");
            PlanNode child = parseQueryBlock(queryBlock.get("ordering_operation"));
            if (child != null) node.getChildren().add(child);
            return node;
        }
        if (queryBlock.has("grouping_operation")) {
            PlanNode node = new PlanNode();
            node.setOperation("Group By");
            PlanNode child = parseQueryBlock(queryBlock.get("grouping_operation"));
            if (child != null) node.getChildren().add(child);
            return node;
        }
        if (queryBlock.has("duplicates_removal")) {
            PlanNode node = new PlanNode();
            node.setOperation("Distinct");
            PlanNode child = parseQueryBlock(queryBlock.get("duplicates_removal"));
            if (child != null) node.getChildren().add(child);
            return node;
        }
        // 多表连接
        if (queryBlock.has("nested_loop")) {
            PlanNode join = new PlanNode();
            join.setOperation("Nested Loop");
            for (JsonNode item : queryBlock.get("nested_loop")) {
                if (item.has("table")) {
                    join.getChildren().add(parseTable(item.get("table")));
                }
            }
            if (join.getChildren().size() == 1) {
                return join.getChildren().get(0);
            }
            return join;
        }
        // 单表
        if (queryBlock.has("table")) {
            return parseTable(queryBlock.get("table"));
        }
        return null;
    }

    private PlanNode parseTable(JsonNode table) {
        PlanNode node = new PlanNode();
        String tableName = table.path("table_name").asText("");
        String accessType = table.path("access_type").asText("");
        node.setOperation("Table Scan");
        node.setTableName(tableName);
        node.setDetail(tableName);
        node.setAccessType(accessType);
        if (table.hasNonNull("key")) {
            node.setKey(table.get("key").asText());
        }
        if (table.hasNonNull("rows_examined_per_scan")) {
            node.setRows(table.get("rows_examined_per_scan").asLong());
        } else if (table.hasNonNull("rows")) {
            node.setRows(table.get("rows").asLong());
        }
        JsonNode ci = table.get("cost_info");
        if (ci != null && ci.hasNonNull("read_cost")) {
            node.setCost(ci.get("read_cost").asText());
        }
        if (table.has("materialized_from_subquery")) {
            JsonNode sub = table.get("materialized_from_subquery").get("query_block");
            if (sub != null) {
                PlanNode child = parseQueryBlock(sub);
                if (child != null) node.getChildren().add(child);
            }
        }
        return node;
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
                // MySQL 表格视图无直接成本，先用扫描行数近似；
                // 随后 enrichWithJsonPlan 若拿到真实 query_cost 会覆盖此值
                result.setTotalCost((double) maxRows);
            }
            return;
        }

        // Oracle：DBMS_XPLAN 的竖线表格，按表头定位 Rows / Cost 列
        if (isOracle(dbType)) {
            parseOraclePlanText(result);
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

    /**
     * 解析 DBMS_XPLAN.DISPLAY 输出的竖线表格，取 Rows / Cost 两列的最大值。
     * 形如：
     * | Id | Operation         | Name   | Rows | Bytes | Cost (%CPU)| Time     |
     * |  0 | SELECT STATEMENT  |        | 1000 | 20000 |    45   (0)| 00:00:01 |
     */
    private void parseOraclePlanText(ExplainResult result) {
        int rowsCol = -1, costCol = -1;
        for (ExplainRow row : result.getRows()) {
            for (Object val : row.getColumns().values()) {
                if (val == null) {
                    continue;
                }
                String line = val.toString();
                if (!line.contains("|")) {
                    continue;
                }
                String[] cells = line.split("\\|", -1);
                // 表头行：定位 Rows / Cost 所在列
                if (line.contains("Operation") && (line.contains("Rows") || line.contains("Cost"))) {
                    for (int i = 0; i < cells.length; i++) {
                        String h = cells[i].trim();
                        if (h.equalsIgnoreCase("Rows")) rowsCol = i;
                        if (h.toUpperCase().startsWith("COST")) costCol = i;
                    }
                    continue;
                }
                if (rowsCol >= 0 && rowsCol < cells.length) {
                    Long r = parseOracleNumber(cells[rowsCol]);
                    if (r != null && (result.getEstimatedRows() == null || r > result.getEstimatedRows())) {
                        result.setEstimatedRows(r);
                    }
                }
                if (costCol >= 0 && costCol < cells.length) {
                    Long c = parseOracleNumber(cells[costCol]);
                    if (c != null && (result.getTotalCost() == null || c > result.getTotalCost())) {
                        result.setTotalCost((double) c);
                    }
                }
            }
        }
    }

    /** Oracle 计划单元格可能是 "1000"、"45   (0)"、"  10M"，取前导数字（K/M/G 换算） */
    private Long parseOracleNumber(String cell) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(\\d+)\\s*([KMG])?").matcher(cell);
        if (!m.find()) {
            return null;
        }
        long base = Long.parseLong(m.group(1));
        String unit = m.group(2);
        if (unit == null) {
            return base;
        }
        return switch (unit) {
            case "K" -> base * 1_000L;
            case "M" -> base * 1_000_000L;
            case "G" -> base * 1_000_000_000L;
            default -> base;
        };
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
     * 检测全表扫描：
     * - MySQL 系看 type 列是否精确等于 ALL / index
     * - 文本计划（PG/DM 等）用词边界匹配，避免 t_call_log、full_name、SMALLINT 之类被误判
     */
    private boolean detectFullScan(ExplainResult result) {
        if (isMysqlFamily(result.getDbType())) {
            for (ExplainRow row : result.getRows()) {
                Object type = findValueIgnoreCase(row, "type");
                if (type != null && "ALL".equalsIgnoreCase(type.toString().trim())) {
                    return true;
                }
            }
            // 计划树中的 access_type 同样只做精确比较
            for (PlanNode node : result.getPlanTree()) {
                if (hasFullScanNode(node)) {
                    return true;
                }
            }
            return false;
        }
        String plan = result.getRawPlan();
        if (plan == null) {
            return false;
        }
        return FULL_SCAN_TEXT.matcher(plan).find();
    }

    private boolean hasFullScanNode(PlanNode node) {
        if (node == null) {
            return false;
        }
        if (node.getAccessType() != null && "ALL".equalsIgnoreCase(node.getAccessType().trim())) {
            return true;
        }
        for (PlanNode child : node.getChildren()) {
            if (hasFullScanNode(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 成本评估 + 给出建议
     */
    private void evaluate(ExplainResult result, String originalSql) {
        Double cost = result.getTotalCost();
        Long rows = result.getEstimatedRows();

        boolean fullScan = detectFullScan(result);

        if (cost == null) {
            result.setCostLevel("UNKNOWN");
            result.getTips().add("无法从执行计划中解析出成本值，请结合上方计划明细人工评估。");
            return;
        }

        // 扫描行数少且没有全表扫描时才判定为无需优化；
        // 带 LIMIT 的全表扫描输出行数很少，但依然要给索引建议
        if (rows != null && rows <= SMALL_ROWS_THRESHOLD && !fullScan) {
            result.setCostLevel("LOW");
            result.getTips().add(String.format(
                    "预估扫描行数很少（约 %d 行），且未检测到全表扫描，无需额外建立索引。", rows));
            return;
        }

        if (cost >= HIGH_COST_THRESHOLD) {
            result.setCostLevel("HIGH");
            result.getTips().add(String.format("执行计划成本偏高（约 %.2f），建议优化。", cost));
        } else if (cost >= MEDIUM_COST_THRESHOLD) {
            result.setCostLevel("MEDIUM");
            result.getTips().add(String.format("执行计划成本中等（约 %.2f），可关注是否有优化空间。", cost));
        } else {
            result.setCostLevel("LOW");
            result.getTips().add(String.format("执行计划成本较低（约 %.2f），当前查询效率良好。", cost));
        }

        // 成本偏高或存在全表扫描时给索引建议（全表扫描即使当前成本不高，数据量涨上来也会劣化）
        if (cost >= HIGH_COST_THRESHOLD || fullScan) {
            if (fullScan) {
                result.getTips().add("检测到全表扫描，以下为基于 SQL 结构给出的索引建议：");
            }
            List<IndexSuggestion> suggestions = indexAnalyzer.analyze(originalSql);
            dataSourceService.detectIndexExistence(suggestions);
            for (IndexSuggestion s : suggestions) {
                fillCreateSql(s);
            }
            result.setIndexSuggestions(suggestions);
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
