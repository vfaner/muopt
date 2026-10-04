package com.qqmu.muopt;

import com.qqmu.muopt.config.LegacyDataMigration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MuOpt（沐优）启动类
 *
 * <p>平台数据源（H2 文件库 ./data）由 Spring Boot 自动配置，仅用于保存多数据源 / 多 AI 模型配置；
 * 用户业务库的连接池由 DataSourceService 按启用配置手工创建与热切换。
 */
@SpringBootApplication
public class MuoptApplication {

    public static void main(String[] args) {
        // 必须在 DataSource 创建前把旧库文件 sql-optimizer.mv.db 迁移为 muopt.mv.db
        LegacyDataMigration.migrateFromEnv();
        SpringApplication.run(MuoptApplication.class, args);
    }
}
