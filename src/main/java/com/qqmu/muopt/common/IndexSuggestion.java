package com.qqmu.muopt.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 索引建议
 * exists 状态决定前端颜色：已存在=灰色，缺失=橙色
 */
@Data
@NoArgsConstructor
public class IndexSuggestion {

    /** 表名 */
    private String tableName;

    /** 涉及的列（有序，组合索引按顺序） */
    private List<String> columns;

    /** 建议的索引名 */
    private String indexName;

    /** 建议原因（如：WHERE 等值过滤 / JOIN 连接列 / ORDER BY 排序列） */
    private String reason;

    /** 生成的 CREATE INDEX 语句 */
    private String createSql;

    /**
     * 索引存在状态：
     * - UNKNOWN：未连接数据源，无法确认（前端橙色）
     * - MISSING：数据源中不存在，建议创建（前端橙色）
     * - EXISTS：数据源中已存在，无需重复创建（前端灰色）
     */
    private String status = "UNKNOWN";

    /** 若已存在，记录已存在的索引名 */
    private String existingIndexName;

    public IndexSuggestion(String tableName, List<String> columns, String reason) {
        this.tableName = tableName;
        this.columns = columns;
        this.reason = reason;
    }
}
