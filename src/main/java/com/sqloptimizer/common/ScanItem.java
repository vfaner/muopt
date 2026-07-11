package com.sqloptimizer.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目扫描出的单条 SQL 及其优化结果
 * 携带文件定位信息（路径、行号），用于展示与后续替换。
 */
@Data
@NoArgsConstructor
public class ScanItem {

    /** 全局唯一 id（前端定位/替换用） */
    private String id;

    /** SQL 所在文件的绝对路径 */
    private String filePath;

    /** 相对项目根目录的路径（前端展示更简洁） */
    private String relativePath;

    /** SQL 起始行号（1 起） */
    private int startLine;

    /** SQL 结束行号（1 起） */
    private int endLine;

    /**
     * 来源类型：
     * MYBATIS_XML / JAVA_STRING / SQL_FILE
     */
    private String sourceType;

    /**
     * 文件中 SQL 的原始文本片段（用于替换时精确匹配，含引号/标签内的原样内容）。
     * 注意：这是文件里真实存在的字符串，替换时按此文本查找。
     */
    private String rawText;

    /** 规整后的源 SQL（去除占位符/拼接后的可解析 SQL） */
    private String sourceSql;

    /** 优化后的 SQL */
    private String optimizedSql;

    /** 索引建议列表 */
    private List<IndexSuggestion> indexSuggestions = new ArrayList<>();

    /** 优化提示 */
    private List<String> tips = new ArrayList<>();

    /** 是否经过 AI 深度优化 */
    private boolean aiOptimized = false;

    /** 是否已被替换 */
    private boolean replaced = false;
}
