package com.sqloptimizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * SQL优化工具启动类
 * 排除默认数据源自动配置——数据源由用户在页面上动态配置（非必需）
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class SqlOptimizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlOptimizerApplication.class, args);
    }
}
