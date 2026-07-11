package com.sqloptimizer.common;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据源配置（内存态，非必需功能）
 */
@Data
@NoArgsConstructor
public class DataSourceConfig {

    /**
     * 数据库类型：
     * mysql / oceanbase / tidb（走 MySQL 驱动）
     * postgresql / gaussdb / opengauss / kingbase（走 PostgreSQL 驱动）
     * oracle（走 Oracle 驱动）
     * dameng（走动态加载的达梦驱动）
     */
    private String dbType;

    private String host;
    private int port;
    private String database;
    private String username;
    private String password;

    /** 连接是否已成功建立 */
    private boolean connected = false;
}
