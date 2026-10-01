package com.qqmu.muopt.service;

import com.qqmu.muopt.common.IndexSuggestion;
import com.qqmu.muopt.common.OptimizeResult;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL 优化编排服务
 * 组合规则引擎（IndexAnalyzerService）、数据源检测（DataSourceService）、AI（AiService），
 * 产出统一的 OptimizeResult。
 *
 * 颜色规则：
 * - 未连接数据源：所有索引建议 status=UNKNOWN（前端橙色）
 * - 已连接数据源：EXISTS（灰色）/ MISSING（橙色）
 */
@Slf4j
@Service
public class SqlOptimizerService {

    private final IndexAnalyzerService indexAnalyzer;
    private final DataSourceService dataSourceService;
    private final AiService aiService;
    private final LocalRewriteService localRewrite;

    @Autowired
    public SqlOptimizerService(IndexAnalyzerService indexAnalyzer,
                               DataSourceService dataSourceService,
                               AiService aiService,
                               LocalRewriteService localRewrite) {
        this.indexAnalyzer = indexAnalyzer;
        this.dataSourceService = dataSourceService;
        this.aiService = aiService;
        this.localRewrite = localRewrite;
    }

    /**
     * 优化单条 SQL
     *
     * @param sql        原始 SQL
     * @param enableAi   是否启用 AI 深度优化
     */
    public OptimizeResult optimize(String sql, boolean enableAi) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String trimmed = sql.trim();

        OptimizeResult result = new OptimizeResult();
        result.setSourceSql(trimmed);
        result.setOptimizedSql(trimmed);

        // 1. 规则引擎提取索引候选
        List<IndexSuggestion> suggestions = indexAnalyzer.analyze(trimmed);

        boolean connected = dataSourceService.isConnected();
        result.setDeepAnalyzed(connected);

        // 2. 数据源深度分析
        if (connected) {
            dataSourceService.detectIndexExistence(suggestions);

            // 冗余索引检测
            Set<String> tables = new LinkedHashSet<>();
            for (IndexSuggestion s : suggestions) {
                tables.add(s.getTableName());
            }
            result.setRedundantIndexes(dataSourceService.detectRedundantIndexes(tables));

            // 小表驱动大表
            applySmallTableDriving(trimmed, result);
        } else {
            // 未连接：全部标记为 UNKNOWN（橙色）
            for (IndexSuggestion s : suggestions) {
                s.setStatus("UNKNOWN");
            }
            result.getTips().add("未配置数据源，索引建议无法核实是否已存在，均以橙色展示，可直接复制创建语句。");
        }

        // 3. 生成索引名与 CREATE 语句
        for (IndexSuggestion s : suggestions) {
            fillCreateSql(s);
        }
        result.setIndexSuggestions(suggestions);

        // 4. 基础优化提示（本地规则）
        addBasicTips(trimmed, result);

        // 5. AI 深度优化（可选）；AI 不可用/失败/超时时自动降级为本地规则改写，
        //    保证用户点了优化至少拿到确定性的规则优化结果，而不是原文不动。
        if (enableAi) {
            if (!aiService.isConfigured()) {
                applyLocalRewrite(trimmed, result, "未启用 AI 模型");
            } else {
                try {
                    String optimized = aiService.optimizeSql(trimmed);
                    if (optimized != null && !optimized.isBlank()) {
                        result.setOptimizedSql(optimized);
                        result.setAiOptimized(true);
                    } else {
                        applyLocalRewrite(trimmed, result, "AI 返回内容为空");
                    }
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? "AI 服务不可用" : e.getMessage();
                    log.info("AI 优化失败，降级本地规则改写: {}", reason);
                    applyLocalRewrite(trimmed, result, "AI 优化失败：" + reason);
                }
            }
        }

        if (suggestions.isEmpty()) {
            result.getTips().add("未从该 SQL 中解析出可优化的索引列（可能是全表操作或语法未覆盖）。");
        }
        return result;
    }

    /**
     * AI 不可用时的降级路径：应用本地规则改写，并在提示中说明降级原因与每条改动。
     */
    private void applyLocalRewrite(String sql, OptimizeResult result, String reason) {
        // 去掉错误信息末尾的句号，避免与拼接的「，已自动…」「；本地规则…」连用
        reason = reason.replaceAll("[。.；;，,\\s]+$", "");
        LocalRewriteService.RewriteOutcome outcome = localRewrite.tryRewrite(sql);
        if (outcome != null) {
            result.setOptimizedSql(outcome.getRewrittenSql());
            result.setLocalRewritten(true);
            result.getTips().add(reason + "，已自动改用本地规则改写：");
            outcome.getChanges().forEach(c -> result.getTips().add("  · " + c));
        } else {
            result.getTips().add(reason + "；本地规则未发现可安全自动改写的写法，原 SQL 保持不变，"
                    + "可参考索引建议与优化提示手动调整。");
        }
    }

    /**
     * 生成索引名与 CREATE INDEX 语句
     */
    private void fillCreateSql(IndexSuggestion s) {
        String idxName = "idx_" + s.getTableName().toLowerCase() + "_"
                + String.join("_", s.getColumns()).toLowerCase();
        // 索引名过长时截断
        if (idxName.length() > 60) {
            idxName = idxName.substring(0, 60);
        }
        s.setIndexName(idxName);
        String cols = String.join(", ", s.getColumns());
        s.setCreateSql("CREATE INDEX " + idxName + " ON " + s.getTableName() + " (" + cols + ");");
    }

    /**
     * 小表驱动大表分析（需数据源）：
     * 统计 JOIN 涉及各表行数，若驱动表（FROM 主表）比被连接表大很多，给出提示。
     */
    private void applySmallTableDriving(String sql, OptimizeResult result) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select select) || !(select.getSelectBody() instanceof PlainSelect plain)) {
                return;
            }
            if (plain.getJoins() == null || plain.getJoins().isEmpty()) {
                return;
            }

            // 收集所有参与表
            LinkedHashMap<String, Long> tableRows = new LinkedHashMap<>();
            String mainTable = (plain.getFromItem() instanceof Table t) ? t.getName() : null;
            if (mainTable != null) {
                tableRows.put(mainTable, dataSourceService.countTableRows(mainTable));
            }
            for (Join join : plain.getJoins()) {
                if (join.getRightItem() instanceof Table jt) {
                    tableRows.put(jt.getName(), dataSourceService.countTableRows(jt.getName()));
                }
            }

            // 过滤掉统计失败的表
            LinkedHashMap<String, Long> valid = new LinkedHashMap<>();
            tableRows.forEach((k, v) -> {
                if (v >= 0) {
                    valid.put(k, v);
                }
            });
            if (valid.size() < 2) {
                return;
            }

            String smallest = null, largest = null;
            long minRows = Long.MAX_VALUE, maxRows = Long.MIN_VALUE;
            for (Map.Entry<String, Long> e : valid.entrySet()) {
                if (e.getValue() < minRows) {
                    minRows = e.getValue();
                    smallest = e.getKey();
                }
                if (e.getValue() > maxRows) {
                    maxRows = e.getValue();
                    largest = e.getKey();
                }
            }

            StringBuilder sb = new StringBuilder("表连接数据量：");
            valid.forEach((k, v) -> sb.append(k).append("≈").append(v).append(" 行  "));
            result.getTips().add(sb.toString().trim());

            if (smallest != null && !smallest.equals(mainTable) && maxRows >= minRows * 10) {
                result.getTips().add(String.format(
                        "建议小表驱动大表：小表【%s】(%d 行) 应作为驱动表，大表【%s】(%d 行) 被驱动。"
                                + "可将小表放在 FROM 主表位置，或使用 STRAIGHT_JOIN / LEADING 提示调整连接顺序。",
                        smallest, minRows, largest, maxRows));
            } else {
                result.getTips().add("表连接顺序合理（驱动表已是较小表），无需调整。");
            }
        } catch (Exception e) {
            log.debug("小表驱动分析失败: {}", e.getMessage());
        }
    }

    /**
     * 基础本地优化提示（不依赖数据源）。
     * 关键字一律用词边界匹配，避免 vendor/color 这类标识符里的 "or" 被当成 OR 条件。
     */
    private void addBasicTips(String sql, OptimizeResult result) {
        String stripped = stripStringLiterals(sql).toUpperCase();
        if (Pattern.compile("SELECT\\s+\\*", Pattern.CASE_INSENSITIVE).matcher(stripped).find()) {
            result.getTips().add("检测到 SELECT *，建议只查询需要的列，减少 IO 与网络传输。");
        }
        // LIKE 的前导 % 要看原始 SQL（字面量已被剥离，这里单独判断）
        if (Pattern.compile("LIKE\\s+['\"]%", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            result.getTips().add("检测到以 % 开头的 LIKE 模糊匹配，无法利用普通索引，考虑全文索引或调整查询方式。");
        }
        if (Pattern.compile("\\bOR\\b").matcher(stripped).find()) {
            result.getTips().add("检测到 OR 条件，可能导致索引失效，可考虑改写为 UNION ALL 或 IN。");
        }
        if (stripped.contains("!=") || stripped.contains("<>")) {
            result.getTips().add("检测到不等于（!= / <>）条件，通常无法使用索引，注意评估过滤效果。");
        }
    }

    /**
     * 把字符串字面量替换为空占位，避免字面量内容触发关键字误报。
     */
    private String stripStringLiterals(String sql) {
        return sql.replaceAll("'(?:''|[^'])*'", "''")
                .replaceAll("\"(?:\"\"|[^\"])*\"", "\"\"");
    }
}
