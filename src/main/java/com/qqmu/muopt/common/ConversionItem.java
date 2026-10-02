package com.qqmu.muopt.common;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConversionItem {
    private String filePath;
    private String sourceSql;
    private String targetSql;
    private String conversionType;
    private int lineNumber;

    /** 该条目的 targetSql 是否经过 AI 优化 */
    private boolean aiOptimized;

    /** AI 优化前的规则转换结果，便于前端对比、也是 AI 结果被拒绝时的兜底 */
    private String ruleSql;

    /**
     * {@code sourceSql} 在源文件中的起始下标；{@code -1} 表示未记录（如手工提交的自定义清单）。
     * <p>自动改造靠这个下标按位置精确替换。早先的做法是拿扫描时归一化过的 SQL 去原文里
     * 逐字 {@code contains} 匹配，而归一化后的文本在原文中并不存在，导致 XML 与多行 Java
     * 几乎全部静默失效。
     */
    private int startOffset = -1;

    /** {@code sourceSql} 在源文件中的结束下标（不含）；{@code -1} 表示未记录 */
    private int endOffset = -1;

    public ConversionItem(String filePath, String sourceSql, String targetSql,
                          String conversionType, int lineNumber) {
        this.filePath = filePath;
        this.sourceSql = sourceSql;
        this.targetSql = targetSql;
        this.conversionType = conversionType;
        this.lineNumber = lineNumber;
    }

    /** 是否记录了可用于按位置替换的原文区间 */
    public boolean hasOffsets() {
        return startOffset >= 0 && endOffset > startOffset;
    }
}
