package com.sqloptimizer.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 优化结果
 */
@Data
@NoArgsConstructor
public class OptimizeResult {

    /** 原始 SQL */
    private String sourceSql;

    /** 优化后的 SQL（含小表驱动大表等改写；无改写时等于原 SQL） */
    private String optimizedSql;

    /** 索引建议列表 */
    private List<IndexSuggestion> indexSuggestions = new ArrayList<>();

    /** 冗余索引建议（需数据源，建议删除的重复索引 DROP 语句） */
    private List<String> redundantIndexes = new ArrayList<>();

    /** 优化提示 / 分析说明 */
    private List<String> tips = new ArrayList<>();

    /** 是否已连接数据源做了深度分析 */
    private boolean deepAnalyzed = false;

    /** 是否经过 AI 深度优化 */
    private boolean aiOptimized = false;

    /** AI 不可用（未配置/失败/超时）时，是否已自动降级为本地规则改写 */
    private boolean localRewritten = false;
}
