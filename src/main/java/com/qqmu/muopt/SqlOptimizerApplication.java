package com.qqmu.muopt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SQL优化工具启动类
 *
 * <p>平台数据源（H2 文件库 ./data）由 Spring Boot 自动配置，仅用于保存多数据源 / 多 AI 模型配置；
 * 用户业务库的连接池由 DataSourceService 按启用配置手工创建与热切换。
 */
@SpringBootApplication
public class SqlOptimizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlOptimizerApplication.class, args);
    }
}
