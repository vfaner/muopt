package com.qqmu.muopt.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地规则改写器：在<strong>不改变查询语义</strong>的前提下做结构化改写。
 *
 * <p>AI 超时/失败时自动降级使用，也可作为无 AI 时的保底改写。规则刻意保守，
 * 只覆盖三类确定性改写：
 * <ol>
 *   <li>逗号隐式连接（FROM a, b WHERE a.id=b.aid）→ ANSI {@code INNER JOIN ... ON}；
 *       只移动两侧表名都能识别的等值条件，无法判定归属的条件继续留在 WHERE；</li>
 *   <li>HAVING 中的非聚合条件下移到 WHERE（前提是存在 GROUP BY，可提前过滤、利用索引）；</li>
 *   <li>已连接数据源时，单表查询的 SELECT * 按元数据展开为具体列。</li>
 * </ol>
 * 任何一步无法安全解析都整体跳过（返回 null），绝不做猜测式改写。
 */
@Slf4j
@Service
public class LocalRewriteService {

    private static final Pattern AGGREGATE_CALL = Pattern.compile(
            "\\b(COUNT|SUM|AVG|MIN|MAX|GROUP_CONCAT|STRING_AGG|ARRAY_AGG)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** 允许自动展开为列名的标识符（含中文列名），特殊字符列名不自动处理 */
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9_$一-龥]+");

    private final DataSourceService dataSourceService;

    public LocalRewriteService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 尝试规则改写。
     *
     * @return 改写结果；没有可安全应用的改写（或解析失败）时返回 null
     */
    public RewriteOutcome tryRewrite(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select select)
                    || !(select.getSelectBody() instanceof PlainSelect plain)) {
                return null;
            }
            List<String> changes = new ArrayList<>();
            rewriteCommaJoins(plain, changes);
            moveNonAggregateHavingToWhere(plain, changes);
            expandSelectStar(plain, changes);

            if (changes.isEmpty()) {
                return null;
            }
            String rewritten = stmt.toString();
            if (rewritten.equals(sql.trim())) {
                return null;
            }
            return new RewriteOutcome(rewritten, changes);
        } catch (Exception e) {
            // 解析失败（方言/动态 SQL 占位等）不影响其他分析，安静跳过
            log.debug("本地规则改写跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 逗号隐式连接 → INNER JOIN：逐个处理 simple join，把 WHERE 中
     * 「新表列 = 已出现表列」的等值条件搬到该 JOIN 的 ON 上。
     */
    private void rewriteCommaJoins(PlainSelect plain, List<String> changes) {
        List<Join> joins = plain.getJoins();
        if (joins == null || joins.isEmpty()
                || !(plain.getFromItem() instanceof Table from)) {
            return;
        }
        Set<String> seenTables = new HashSet<>();
        seenTables.add(tableKey(from));
        Expression where = plain.getWhere();
        boolean changed = false;

        for (Join join : joins) {
            boolean isCommaJoin = join.isSimple()
                    && join.getRightItem() instanceof Table right;
            if (isCommaJoin && where != null) {
                String rightKey = tableKey((Table) join.getRightItem());
                List<Expression> onConditions = new ArrayList<>();
                List<Expression> remainConditions = new ArrayList<>();

                for (Expression term : splitAnd(where)) {
                    String[] pair = equiColPair(term);
                    String matchedOther = null;
                    if (pair != null) {
                        if (pair[0].equals(rightKey) && seenTables.contains(pair[1])) {
                            matchedOther = pair[1];
                        } else if (pair[1].equals(rightKey) && seenTables.contains(pair[0])) {
                            matchedOther = pair[0];
                        }
                    }
                    // 只搬能确定是「新表 ↔ 已出现表」的等值条件，其余一律留在 WHERE
                    if (matchedOther != null) {
                        onConditions.add(term);
                    } else {
                        remainConditions.add(term);
                    }
                }

                if (!onConditions.isEmpty()) {
                    join.setSimple(false);
                    join.setInner(true);
                    join.setOnExpression(combineAnd(onConditions));
                    where = combineAnd(remainConditions);
                    changed = true;
                }
            }
            if (join.getRightItem() instanceof Table t) {
                seenTables.add(tableKey(t));
            }
        }

        if (changed) {
            plain.setWhere(where);
            changes.add("将逗号隐式连接（FROM a, b WHERE …）改写为 ANSI INNER JOIN … ON，连接关系更明确");
        }
    }

    /**
     * HAVING 中的非聚合过滤条件下移到 WHERE。仅在存在 GROUP BY 时处理，
     * 含聚合函数或子查询的条件保留在 HAVING。
     */
    private void moveNonAggregateHavingToWhere(PlainSelect plain, List<String> changes) {
        Expression having = plain.getHaving();
        if (having == null || plain.getGroupBy() == null) {
            return;
        }
        List<Expression> pushedDown = new ArrayList<>();
        List<Expression> kept = new ArrayList<>();
        for (Expression term : splitAnd(having)) {
            if (canPushToWhere(term)) {
                pushedDown.add(term);
            } else {
                kept.add(term);
            }
        }
        if (pushedDown.isEmpty()) {
            return;
        }
        List<Expression> whereTerms = new ArrayList<>();
        if (plain.getWhere() != null) {
            whereTerms.addAll(splitAnd(plain.getWhere()));
        }
        whereTerms.addAll(pushedDown);
        plain.setWhere(combineAnd(whereTerms));
        plain.setHaving(combineAnd(kept));
        changes.add("HAVING 中的非聚合过滤条件下移到 WHERE，在分组前提前过滤、更容易利用索引");
    }

    private boolean canPushToWhere(Expression term) {
        if (term == null) {
            return false;
        }
        if (AGGREGATE_CALL.matcher(term.toString()).find()) {
            return false;
        }
        if (containsSubSelect(term)) {
            return false;
        }
        return true;
    }

    private boolean containsSubSelect(Expression expression) {
        boolean[] found = {false};
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect subSelect) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 单表查询的 SELECT * 在已连接数据源时按元数据展开为具体列，
     * 列名含特殊字符（需引用）时保守跳过。
     */
    private void expandSelectStar(PlainSelect plain, List<String> changes) {
        if (!(plain.getFromItem() instanceof Table table)) {
            return;
        }
        if (plain.getJoins() != null && !plain.getJoins().isEmpty()) {
            return;
        }
        List<SelectItem<?>> items = plain.getSelectItems();
        if (items == null || items.size() != 1
                || !(items.get(0).getExpression() instanceof AllColumns)) {
            return;
        }
        List<String> columns = dataSourceService.getColumnNames(table.getName());
        if (columns.isEmpty()) {
            return;
        }
        for (String c : columns) {
            if (!SAFE_IDENTIFIER.matcher(c).matches()) {
                log.debug("表 {} 列名含特殊字符，跳过 SELECT * 自动展开: {}", table.getName(), c);
                return;
            }
        }
        List<SelectItem<?>> expanded = new ArrayList<>();
        for (String column : columns) {
            expanded.add(new SelectItem<>(new Column(column)));
        }
        plain.setSelectItems(expanded);
        changes.add("已连接数据源读取表结构，SELECT * 展开为 " + columns.size()
                + " 个具体列，避免读取无用列的 IO 与网络传输");
    }

    /** 表在条件中的限定名键：有别名用别名，否则用表名（统一小写） */
    private String tableKey(Table table) {
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            return table.getAlias().getName().toLowerCase();
        }
        return table.getName().toLowerCase();
    }

    /**
     * 若表达式是「列 = 列」且两列都带表限定名，返回两侧限定名（小写）；否则 null。
     * 不带表名的列无法安全判定归属，不参与连接条件搬迁。
     */
    private String[] equiColPair(Expression term) {
        if (!(term instanceof EqualsTo eq)) {
            return null;
        }
        if (eq.getLeftExpression() instanceof Column left
                && eq.getRightExpression() instanceof Column right
                && left.getTable() != null && !left.getTable().getName().isBlank()
                && right.getTable() != null && !right.getTable().getName().isBlank()) {
            return new String[]{left.getTable().getName().toLowerCase(),
                    right.getTable().getName().toLowerCase()};
        }
        return null;
    }

    private List<Expression> splitAnd(Expression expression) {
        List<Expression> terms = new ArrayList<>();
        flattenAnd(expression, terms);
        return terms;
    }

    private void flattenAnd(Expression expression, List<Expression> out) {
        if (expression instanceof AndExpression and) {
            flattenAnd(and.getLeftExpression(), out);
            flattenAnd(and.getRightExpression(), out);
        } else if (expression != null) {
            out.add(expression);
        }
    }

    private Expression combineAnd(List<Expression> terms) {
        Expression combined = null;
        for (Expression term : terms) {
            combined = (combined == null) ? term : new AndExpression(combined, term);
        }
        return combined;
    }

    /** 改写结果 */
    @Getter
    public static class RewriteOutcome {
        private final String rewrittenSql;
        /** 应用的改写说明（人类可读，逐条） */
        private final List<String> changes;

        public RewriteOutcome(String rewrittenSql, List<String> changes) {
            this.rewrittenSql = rewrittenSql;
            this.changes = changes;
        }
    }
}
