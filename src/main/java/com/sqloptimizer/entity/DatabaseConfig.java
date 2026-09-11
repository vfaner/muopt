package com.sqloptimizer.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 数据库连接配置。可保存多条，但同一时刻只有一条 {@link #enabled} = true
 * （互斥逻辑在 DatabaseConfigService 事务内保证）。
 */
@Entity
@Table(name = "database_config")
@Getter
@Setter
public class DatabaseConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    /**
     * 数据库类型：
     * mysql / oceanbase / tidb（MySQL 驱动）
     * postgresql / gaussdb（PostgreSQL 驱动）、opengauss（原厂驱动）、kingbase（原厂驱动）
     * oracle（Oracle 驱动）
     * dameng（内置达梦 DmJdbcDriver18）
     */
    @Column(name = "db_type", nullable = false, length = 32)
    private String dbType;

    @Column(length = 255)
    private String host;

    private Integer port;

    /** 数据库名 / Oracle 服务名 / 达梦 schema */
    @Column(name = "database_name", length = 255)
    private String databaseName;

    @Column(length = 255)
    private String username;

    /** 自定义类型（dbType=custom）时使用完整 JDBC URL，原样使用 */
    @Lob
    @Column(name = "custom_url")
    private String customUrl;

    /** 自定义类型或覆盖预设类型时使用的驱动类名 */
    @Column(name = "custom_driver", length = 255)
    private String customDriver;

    /** 服务器本地驱动 jar 路径（文件或目录，多个可用 ; 或 , 分隔），运行时加载 */
    @Column(name = "custom_jar_path", length = 1024)
    private String customJarPath;

    /** 额外 JDBC URL 参数，格式 k=v&k=v（; 分隔风格的 URL 自动适配） */
    @Lob
    @Column(name = "extra_params")
    private String extraParams;

    /** 加密存储（enc: 前缀）；只接收表单明文，响应永不回传 */
    @Lob
    @Column(name = "password_enc")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 是否为当前启用的连接（全局至多一条） */
    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
