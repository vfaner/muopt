package com.qqmu.muopt.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 旧配置库文件名启动迁移。
 *
 * <p>历史版本的 H2 配置库文件名为 data/sql-optimizer.mv.db，现统一为 data/muopt.mv.db。
 * 升级后首次启动时把旧文件改名为新文件，保证账号 / 数据源 / AI 模型配置不丢失；
 * 新文件已存在或旧文件不存在时不做任何事。
 *
 * <p>必须在 Spring 创建 DataSource 之前执行，因此挂在 main() 里、SpringApplication.run 之前。
 */
public final class LegacyDataMigration {

    private static final String LEGACY_PREFIX = "sql-optimizer";
    private static final String NEW_PREFIX = "muopt";
    private static final String[] SUFFIXES = {".mv.db", ".trace.db"};

    private LegacyDataMigration() {
    }

    /**
     * 按 APP_DATA_DIR 环境变量解析配置库目录（与 application.yml 的
     * {@code app.data.dir=${APP_DATA_DIR:./data}} 保持一致）并执行迁移。
     */
    public static void migrateFromEnv() {
        String dir = System.getenv("APP_DATA_DIR");
        migrate(Paths.get(dir == null || dir.isBlank() ? "./data" : dir));
    }

    public static void migrate(Path dataDir) {
        if (Files.exists(dataDir.resolve(NEW_PREFIX + ".mv.db"))
                || !Files.exists(dataDir.resolve(LEGACY_PREFIX + ".mv.db"))) {
            return;
        }
        for (String suffix : SUFFIXES) {
            Path from = dataDir.resolve(LEGACY_PREFIX + suffix);
            Path to = dataDir.resolve(NEW_PREFIX + suffix);
            if (!Files.exists(from)) {
                continue;
            }
            try {
                Files.move(from, to);
                System.out.println("[MuOpt] 配置库文件已迁移: " + from + " -> " + to);
            } catch (IOException e) {
                // 迁移失败不阻断启动：H2 会以新库名建空库，旧文件原样保留可手工恢复
                System.err.println("[MuOpt] 配置库文件迁移失败（将以新库名启动，旧文件保留）: " + e.getMessage());
            }
        }
    }
}
