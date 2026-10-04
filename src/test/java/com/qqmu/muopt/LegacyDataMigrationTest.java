package com.qqmu.muopt;

import com.qqmu.muopt.config.LegacyDataMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧配置库文件名（sql-optimizer.*）到 muopt.* 的启动迁移。
 */
class LegacyDataMigrationTest {

    @TempDir
    Path dir;

    @Test
    void renamesLegacyDatabaseFilesToMuopt() throws IOException {
        Files.writeString(dir.resolve("sql-optimizer.mv.db"), "legacy");
        Files.writeString(dir.resolve("sql-optimizer.trace.db"), "trace");

        LegacyDataMigration.migrate(dir);

        assertTrue(Files.exists(dir.resolve("muopt.mv.db")));
        assertTrue(Files.exists(dir.resolve("muopt.trace.db")));
        assertFalse(Files.exists(dir.resolve("sql-optimizer.mv.db")));
        assertFalse(Files.exists(dir.resolve("sql-optimizer.trace.db")));
        assertEquals("legacy", Files.readString(dir.resolve("muopt.mv.db")));
        assertEquals("trace", Files.readString(dir.resolve("muopt.trace.db")));
    }

    @Test
    void keepsLegacyFilesWhenNewDatabaseAlreadyExists() throws IOException {
        Files.writeString(dir.resolve("sql-optimizer.mv.db"), "legacy");
        Files.writeString(dir.resolve("muopt.mv.db"), "current");

        LegacyDataMigration.migrate(dir);

        assertTrue(Files.exists(dir.resolve("sql-optimizer.mv.db")));
        assertEquals("current", Files.readString(dir.resolve("muopt.mv.db")));
    }

    @Test
    void doesNothingWhenNoLegacyDatabase() throws IOException {
        LegacyDataMigration.migrate(dir);

        assertFalse(Files.exists(dir.resolve("muopt.mv.db")));
    }
}
