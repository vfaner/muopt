package com.qqmu.muopt.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * 数据库密码 / AI API Key 落库加密（移植自 synctool）。
 *
 * <p>密文带 {@value #PREFIX} 前缀：重复保存时不会二次加密，旧版本留下的明文也能照常读取。
 */
@Component
@Slf4j
public class CryptoUtil {

    private static final String PREFIX = "enc:";

    private final TextEncryptor encryptor;

    public CryptoUtil(@Value("${app.crypto.password}") String password,
                      @Value("${app.crypto.salt}") String salt) {
        // Delux encryptor：AES-256 CBC，每个值随机 IV（salt 须为 16 位十六进制）
        this.encryptor = Encryptors.delux(password, salt);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        return PREFIX + encryptor.encrypt(plain);
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!isEncrypted(stored)) {
            // 加密功能上线前写入的明文，原样使用
            return stored;
        }
        try {
            return encryptor.decrypt(stored.substring(PREFIX.length()));
        } catch (Exception e) {
            log.error("无法解密已存储的密钥/密码，可能是加密口令已变更，请在页面重新填写。");
            throw new IllegalStateException("密钥解密失败（加密口令可能已变更，请重新填写密码/API Key）", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
