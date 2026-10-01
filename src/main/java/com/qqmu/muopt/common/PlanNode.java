package com.qqmu.muopt.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行计划节点（树形/图形展示用）
 * 由数据库的结构化执行计划（如 MySQL EXPLAIN FORMAT=JSON）解析而来。
 */
@Data
@NoArgsConstructor
public class PlanNode {

    /** 操作名，如 Nested Loop / Table Scan / Sort / Group */
    private String operation;

    /** 明细，如表名或连接条件 */
    private String detail;

    /** 表名 */
    private String tableName;

    /** 访问类型（MySQL）：ALL / ref / eq_ref / index / range / const 等 */
    private String accessType;

    /** 使用的索引 */
    private String key;

    /** 预估行数 */
    private Long rows;

    /** 该节点成本（原样字符串，便于展示） */
    private String cost;

    /** 子节点 */
    private List<PlanNode> children = new ArrayList<>();
}
