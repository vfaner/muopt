package com.qqmu.muopt.util;

import com.qqmu.muopt.common.IndexSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexDdlTest {

    @Test
    void buildsIndexNameAndCreateSql() {
        IndexSuggestion s = new IndexSuggestion();
        s.setTableName("ORDERS");
        s.setColumns(List.of("USER_ID", "STATUS"));

        IndexDdl.fill(s);

        assertEquals("idx_orders_user_id_status", s.getIndexName());
        assertEquals("CREATE INDEX idx_orders_user_id_status ON ORDERS (USER_ID, STATUS);",
                s.getCreateSql());
    }

    @Test
    void truncatesLongIndexNamesTo60Chars() {
        IndexSuggestion s = new IndexSuggestion();
        s.setTableName("a_very_very_long_table_name_that_keeps_going");
        s.setColumns(List.of("and_one_really_long_column_name_too"));

        IndexDdl.fill(s);

        assertTrue(s.getIndexName().length() <= 60);
        assertTrue(s.getCreateSql().startsWith("CREATE INDEX " + s.getIndexName()));
    }
}
