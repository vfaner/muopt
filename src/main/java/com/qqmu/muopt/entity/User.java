package com.qqmu.muopt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 登录用户。系统为单用户形态：默认初始化账号 admin（密码 123456），
 * 可在「个人中心」修改昵称 / 账号 / 密码。密码用 BCrypt 哈希后落库，永不回传。
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录账号，全局唯一 */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 密码哈希，响应永不回传 */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    /** 展示昵称 */
    @Column(nullable = false, length = 64)
    private String nickname;

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