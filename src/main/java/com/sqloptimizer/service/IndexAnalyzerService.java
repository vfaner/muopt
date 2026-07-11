package com.sqloptimizer.service;

import com.sqloptimizer.common.IndexSuggestion;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 索引候选列分析器
 * 基于 JSqlParser 解析 AST，从 WHERE 等值/范围条件、JOIN ON、ORDER BY、GROUP BY 中提取候选索引列。
 *
 * 规则要点：
 * - 等值条件（=, IN）优先级最高，放在组合索引最前
 * - 范围条件（>, <, BETWEEN, LIKE 前缀）放在等值列之后
 * - JOIN 连接列单独建议索引
 * - ORDER BY / GROUP BY 列可与 WHERE 等值列组合
 */
@Slf4j
@Service
public class IndexAnalyzerService {

    /**
     * 分析 SQL，产出索引建议（不含 exists 状态，状态由数据源检测阶段填充）
     */
    public List<IndexSuggestion> analyze(String sql) {
        List<IndexSuggestion> suggestions = new ArrayList<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select select) {
                analyzeSelect(select, suggestions);
            } else if (stmt instanceof Update update) {
                analyzeWhere(update.getWhere(), resolveMainTable(update.getTable()), suggestions, "UPDATE 的 WHERE 过滤列");
            } else if (stmt instanceof Delete delete) {
                analyzeWhere(delete.getWhere(), resolveMainTable(delete.getTable()), suggestions, "DELETE 的 WHERE 过滤列");
            }
        } catch (Exception e) {
            log.warn("SQL 解析失败，降级为无索引建议: {}", e.getMessage());
        }
        return dedup(suggestions);
    }

    private void analyzeSelect(Select select, List<IndexSuggestion> suggestions) {
        if (!(select.getSelectBody() instanceof PlainSelect plain)) {
            return;
        }

        // 主表
        String mainTable = null;
        if (plain.getFromItem() instanceof Table t) {
            mainTable = t.getName();
        }

        // WHERE 条件列
        analyzeWhere(plain.getWhere(), mainTable, suggestions, "WHERE 过滤列");

        // JOIN 连接列
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                String joinTable = (join.getRightItem() instanceof Table jt) ? jt.getName() : null;
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        analyzeJoinOn(on, suggestions);
                    }
                }
            }
        }

        // ORDER BY 列
        if (plain.getOrderByElements() != null) {
            for (OrderByElement ob : plain.getOrderByElements()) {
                if (ob.getExpression() instanceof Column col) {
                    ColumnRef ref = toRef(col, mainTable);
                    if (ref != null) {
                        addSuggestion(suggestions, ref.table, Collections.singletonList(ref.column), "ORDER BY 排序列");
                    }
                }
            }
        }

        // GROUP BY 列
        if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressionList() != null) {
            for (Object ge : plain.getGroupBy().getGroupByExpressionList()) {
                if (ge instanceof Column col) {
                    ColumnRef ref = toRef(col, mainTable);
                    if (ref != null) {
                        addSuggestion(suggestions, ref.table, Collections.singletonList(ref.column), "GROUP BY 分组列");
                    }
                }
            }
        }
    }

    /**
     * 分析 WHERE 表达式，把同一个表的等值列组合为一个索引，范围列附在其后
     */
    private void analyzeWhere(Expression where, String defaultTable, List<IndexSuggestion> suggestions, String reason) {
        if (where == null) {
            return;
        }
        // 按表分组收集等值列与范围列
        Map<String, LinkedHashSet<String>> equalCols = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> rangeCols = new LinkedHashMap<>();
        collectWhereColumns(where, defaultTable, equalCols, rangeCols);

        Set<String> tables = new LinkedHashSet<>();
        tables.addAll(equalCols.keySet());
        tables.addAll(rangeCols.keySet());

        for (String table : tables) {
            List<String> combined = new ArrayList<>();
            if (equalCols.containsKey(table)) {
                combined.addAll(equalCols.get(table));
            }
            if (rangeCols.containsKey(table)) {
                for (String c : rangeCols.get(table)) {
                    if (!combined.contains(c)) {
                        combined.add(c);
                    }
                }
            }
            if (!combined.isEmpty()) {
                addSuggestion(suggestions, table, combined, reason);
            }
        }
    }

    /**
     * 递归收集 WHERE 里的列，区分等值/范围。遇到 OR 则不组合（OR 分支各列独立更保险）
     */
    private void collectWhereColumns(Expression expr, String defaultTable,
                                     Map<String, LinkedHashSet<String>> equalCols,
                                     Map<String, LinkedHashSet<String>> rangeCols) {
        if (expr instanceof AndExpression and) {
            collectWhereColumns(and.getLeftExpression(), defaultTable, equalCols, rangeCols);
            collectWhereColumns(and.getRightExpression(), defaultTable, equalCols, rangeCols);
        } else if (expr instanceof OrExpression or) {
            // OR 两侧列各自作为独立范围候选，避免错误组合
            collectWhereColumns(or.getLeftExpression(), defaultTable, rangeCols, rangeCols);
            collectWhereColumns(or.getRightExpression(), defaultTable, rangeCols, rangeCols);
        } else if (expr instanceof Parenthesis p) {
            collectWhereColumns(p.getExpression(), defaultTable, equalCols, rangeCols);
        } else if (expr instanceof EqualsTo eq) {
            addColumnFromComparison(eq.getLeftExpression(), eq.getRightExpression(), defaultTable, equalCols);
        } else if (expr instanceof InExpression in) {
            if (in.getLeftExpression() instanceof Column col) {
                putColumn(equalCols, toRef(col, defaultTable));
            }
        } else if (expr instanceof Between between) {
            if (between.getLeftExpression() instanceof Column col) {
                putColumn(rangeCols, toRef(col, defaultTable));
            }
        } else if (expr instanceof GreaterThan gt) {
            addColumnFromComparison(gt.getLeftExpression(), gt.getRightExpression(), defaultTable, rangeCols);
        } else if (expr instanceof GreaterThanEquals gte) {
            addColumnFromComparison(gte.getLeftExpression(), gte.getRightExpression(), defaultTable, rangeCols);
        } else if (expr instanceof MinorThan mt) {
            addColumnFromComparison(mt.getLeftExpression(), mt.getRightExpression(), defaultTable, rangeCols);
        } else if (expr instanceof MinorThanEquals mte) {
            addColumnFromComparison(mte.getLeftExpression(), mte.getRightExpression(), defaultTable, rangeCols);
        } else if (expr instanceof LikeExpression like) {
            // 仅前缀匹配（不以 % 开头）的 LIKE 才能用索引
            if (like.getLeftExpression() instanceof Column col
                    && like.getRightExpression() instanceof StringValue sv
                    && !sv.getValue().startsWith("%")) {
                putColumn(rangeCols, toRef(col, defaultTable));
            }
        }
    }

    /**
     * JOIN ON 条件：两侧都是列时，各自建议索引
     */
    private void analyzeJoinOn(Expression on, List<IndexSuggestion> suggestions) {
        if (on instanceof AndExpression and) {
            analyzeJoinOn(and.getLeftExpression(), suggestions);
            analyzeJoinOn(and.getRightExpression(), suggestions);
        } else if (on instanceof EqualsTo eq) {
            if (eq.getLeftExpression() instanceof Column lc) {
                ColumnRef ref = toRef(lc, null);
                if (ref != null && ref.table != null) {
                    addSuggestion(suggestions, ref.table, Collections.singletonList(ref.column), "JOIN 连接列");
                }
            }
            if (eq.getRightExpression() instanceof Column rc) {
                ColumnRef ref = toRef(rc, null);
                if (ref != null && ref.table != null) {
                    addSuggestion(suggestions, ref.table, Collections.singletonList(ref.column), "JOIN 连接列");
                }
            }
        }
    }

    private void addColumnFromComparison(Expression left, Expression right, String defaultTable,
                                         Map<String, LinkedHashSet<String>> target) {
        if (left instanceof Column col) {
            putColumn(target, toRef(col, defaultTable));
        } else if (right instanceof Column col) {
            putColumn(target, toRef(col, defaultTable));
        }
    }

    private void putColumn(Map<String, LinkedHashSet<String>> map, ColumnRef ref) {
        if (ref == null || ref.table == null) {
            return;
        }
        map.computeIfAbsent(ref.table, k -> new LinkedHashSet<>()).add(ref.column);
    }

    private ColumnRef toRef(Column col, String defaultTable) {
        if (col == null) {
            return null;
        }
        String table = null;
        if (col.getTable() != null && col.getTable().getName() != null) {
            table = col.getTable().getName();
        } else {
            table = defaultTable;
        }
        return new ColumnRef(table, col.getColumnName());
    }

    private String resolveMainTable(Table table) {
        return table != null ? table.getName() : null;
    }

    private void addSuggestion(List<IndexSuggestion> suggestions, String table, List<String> columns, String reason) {
        if (table == null || columns == null || columns.isEmpty()) {
            return;
        }
        suggestions.add(new IndexSuggestion(stripQuote(table), stripColumns(columns), reason));
    }

    private List<String> stripColumns(List<String> columns) {
        List<String> result = new ArrayList<>();
        for (String c : columns) {
            result.add(stripQuote(c));
        }
        return result;
    }

    private String stripQuote(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("[\"`\\[\\]]", "").trim();
    }

    /**
     * 去重：同表同列组合只保留一条，合并 reason
     */
    private List<IndexSuggestion> dedup(List<IndexSuggestion> list) {
        Map<String, IndexSuggestion> map = new LinkedHashMap<>();
        for (IndexSuggestion s : list) {
            String key = (s.getTableName() + "|" + String.join(",", s.getColumns())).toLowerCase();
            if (map.containsKey(key)) {
                IndexSuggestion exist = map.get(key);
                if (!exist.getReason().contains(s.getReason())) {
                    exist.setReason(exist.getReason() + " / " + s.getReason());
                }
            } else {
                map.put(key, s);
            }
        }
        return new ArrayList<>(map.values());
    }

    @Data
    private static class ColumnRef {
        final String table;
        final String column;

        ColumnRef(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }
}
