package com.sqloptimizer.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行计划中的一行（类似 Navicat 展示的 EXPLAIN 结果行）
 * 用 LinkedHashMap 保留列顺序，适配不同数据库 EXPLAIN 输出的差异
 */
@Data
@NoArgsConstructor
public class ExplainRow {

    /** 该行的所有字段（列名 -> 值），顺序即数据库返回顺序 */
    private Map<String, Object> columns = new LinkedHashMap<>();

    public void put(String key, Object value) {
        this.columns.put(key, value);
    }
}
