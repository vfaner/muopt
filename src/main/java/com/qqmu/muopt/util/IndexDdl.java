package com.qqmu.muopt.util;

import com.qqmu.muopt.common.IndexSuggestion;

/**
 * 索引建议 DDL 生成：统一「索引名 + CREATE INDEX 语句」的生成规则。
 *
 * <p>原在 SqlOptimizerService 与 ExplainService 各有一份完全相同的私有实现，
 * 抽到本类单点维护。
 */
public final class IndexDdl {

    /** 索引名长度上限（多数数据库标识符上限约 30~128 字符，取保守值 60） */
    private static final int MAX_INDEX_NAME_LENGTH = 60;

    private IndexDdl() {
    }

    /** 就地填充 indexName 与 createSql */
    public static void fill(IndexSuggestion s) {
        String idxName = "idx_" + s.getTableName().toLowerCase() + "_"
                + String.join("_", s.getColumns()).toLowerCase();
        if (idxName.length() > MAX_INDEX_NAME_LENGTH) {
            idxName = idxName.substring(0, MAX_INDEX_NAME_LENGTH);
        }
        s.setIndexName(idxName);
        s.setCreateSql("CREATE INDEX " + idxName + " ON " + s.getTableName()
                + " (" + String.join(", ", s.getColumns()) + ");");
    }
}
