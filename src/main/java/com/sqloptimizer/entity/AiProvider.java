package com.sqloptimizer.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * AI 模型端点配置。可保存多条，但同一时刻只有一条 {@link #enabled} = true，
 * SQL 深度优化始终使用启用的那一条。互斥在 AiProviderService 事务内保证，
 * 而不是靠浏览器禁用按钮——两个打开的页面不能同时各开一个。
 */
@Entity
@Table(name = "ai_provider")
@Getter
@Setter
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiProtocol protocol = AiProtocol.OPENAI;

    /** 端点根地址，如 https://dashscope.aliyuncs.com/compatible-mode/v1 或内网地址 */
    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(length = 128)
    private String model;

    /** 加密存储（enc: 前缀）；只接收表单明文，响应永不回传 */
    @Lob
    @Column(name = "api_key_enc")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    /** 全局至多一条启用 */
    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "max_tokens")
    private Integer maxTokens = 4096;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds = 60;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    /** null 表示从未探测，UI 与探测失败区别展示 */
    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message", length = 512)
    private String lastTestMessage;

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
