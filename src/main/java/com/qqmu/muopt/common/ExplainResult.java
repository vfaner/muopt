package com.qqmu.muopt.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行计划分析结果
 */
@Data
@NoArgsConstructor
public class ExplainResult {

    /** EXPLAIN 表头（列名，有序） */
    private List<String> headers = new ArrayList<>();

    /** EXPLAIN 数据行 */
    private List<ExplainRow> rows = new ArrayList<>();

    /** 原始计划文本（部分数据库返回的是纯文本计划树） */
    private String rawPlan;

    /** 估算总成本（能解析出来时填充，否则为 null） */
    private Double totalCost;

    /** 估算扫描行数 */
    private Long estimatedRows;

    /** 成本评级：LOW / MEDIUM / HIGH */
    private String costLevel;

    /** 分析结论 / 建议 */
    private List<String> tips = new ArrayList<>();

    /** 基于执行计划给出的索引建议 */
    private List<IndexSuggestion> indexSuggestions = new ArrayList<>();

    /** 结构化执行计划树（树形/图形视图用），根节点集合 */
    private List<PlanNode> planTree = new ArrayList<>();

    /** 调试用：数据库返回的原始 JSON 计划（MySQL FORMAT=JSON），便于排查解析问题 */
    private String rawJson;

    /** 调试用：连接的数据库类型 */
    private String dbType;
}
