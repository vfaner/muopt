package com.sqloptimizer.service;

import com.sqloptimizer.entity.DatabaseConfig;
import com.sqloptimizer.repository.DatabaseConfigRepository;
import com.sqloptimizer.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 数据库连接配置的 CRUD 与“唯一启用”标志管理（不负责连接池生命周期，见 DataSourceService）。
 *
 * <p>保存与启用分离：普通保存不会改变 enabled；互斥切换走 {@link #enableExclusive(Long)}。
 */
@Service
@Slf4j
public class DatabaseConfigService {

    private final DatabaseConfigRepository repository;
    private final CryptoUtil cryptoUtil;

    public DatabaseConfigService(DatabaseConfigRepository repository, CryptoUtil cryptoUtil) {
        this.repository = repository;
        this.cryptoUtil = cryptoUtil;
    }

    public List<DatabaseConfig> findAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public Optional<DatabaseConfig> findById(Long id) {
        return repository.findById(id);
    }

    public Optional<DatabaseConfig> active() {
        return repository.findFirstByEnabledTrue();
    }

    /**
     * 新建或更新连接。
     *
     * <p>密码加密后存储；更新时密码留空表示“沿用原密码”，编辑表单无需回传密钥。
     * 普通保存不改变启用状态（新建默认未启用），启用走独立的激活接口。
     */
    @Transactional
    public DatabaseConfig save(DatabaseConfig config, String rawPassword) {
        validate(config);

        boolean wasEnabled;
        if (config.getId() != null) {
            DatabaseConfig existing = repository.findById(config.getId())
                    .orElseThrow(() -> new IllegalArgumentException("数据源配置不存在"));
            if (rawPassword == null || rawPassword.isEmpty()) {
                config.setPassword(existing.getPassword());
            } else {
                config.setPassword(cryptoUtil.encrypt(rawPassword));
            }
            config.setCreatedAt(existing.getCreatedAt());
            wasEnabled = existing.isEnabled();
        } else {
            config.setPassword(cryptoUtil.encrypt(rawPassword));
            wasEnabled = false;
        }
        // enabled 只能由激活/停用接口修改
        config.setEnabled(wasEnabled);

        if (config.getPort() == null) {
            config.setPort(defaultPort(config.getDbType()));
        }

        DatabaseConfig saved = repository.save(config);
        log.info("保存数据库连接 '{}' ({})", saved.getName(), saved.getDbType());
        return saved;
    }

    private void validate(DatabaseConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("连接名称不能为空");
        }
        config.setName(config.getName().trim());
        repository.findByName(config.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(config.getId())) {
                throw new IllegalArgumentException("连接名称已存在: " + config.getName());
            }
        });
        if (config.getDbType() == null || config.getDbType().isBlank()) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        if (isCustom(config)) {
            // 自定义类型：完整 JDBC URL 与驱动类名是开连接的最低要求
            if (config.getCustomUrl() == null || config.getCustomUrl().isBlank()) {
                throw new IllegalArgumentException("自定义类型必须填写完整 JDBC URL");
            }
            if (!config.getCustomUrl().trim().startsWith("jdbc:")) {
                throw new IllegalArgumentException("JDBC URL 必须以 jdbc: 开头");
            }
            if (config.getCustomDriver() == null || config.getCustomDriver().isBlank()) {
                throw new IllegalArgumentException("自定义类型必须填写驱动类名");
            }
        } else {
            if (config.getHost() == null || config.getHost().isBlank()) {
                throw new IllegalArgumentException("主机地址不能为空");
            }
            if (config.getDatabaseName() == null || config.getDatabaseName().isBlank()) {
                throw new IllegalArgumentException("数据库名 / Schema 不能为空");
            }
        }
        // 自定义类型（如内嵌库/免密库）允许空密码
        if (!isCustom(config) && config.getId() == null
                && (config.getPassword() == null || config.getPassword().isEmpty())) {
            throw new IllegalArgumentException("密码不能为空");
        }
    }

    private static boolean isCustom(DatabaseConfig config) {
        return "custom".equalsIgnoreCase(config.getDbType());
    }

    /** 已启用的配置不允许删除，必须先停用，避免活动连接被静默拆掉 */
    @Transactional
    public void delete(Long id) {
        DatabaseConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源配置不存在"));
        if (config.isEnabled()) {
            throw new IllegalStateException("请先停用该数据源，再删除");
        }
        repository.deleteById(id);
        log.info("删除数据库连接 '{}'", config.getName());
    }

    /**
     * 在同一事务内关闭其他所有启用标志并置位目标——互斥的唯一入口。
     * 连接池验证由调用方（DataSourceService）在事务外先行完成。
     */
    @Transactional
    public DatabaseConfig enableExclusive(Long id) {
        DatabaseConfig target = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源配置不存在"));
        repository.disableAll();
        // disableAll 是批量更新，上面的实例可能已过期，重读再置位
        target = repository.findById(id).orElseThrow();
        target.setEnabled(true);
        return repository.save(target);
    }

    @Transactional
    public void disable(Long id) {
        repository.findById(id).ifPresent(config -> {
            config.setEnabled(false);
            repository.save(config);
            log.info("停用数据库连接 '{}'", config.getName());
        });
    }

    /** 各数据库默认端口，用户未填端口时使用 */
    public static int defaultPort(String dbType) {
        if (dbType == null) {
            return 3306;
        }
        return switch (dbType.toLowerCase()) {
            case "oracle" -> 1521;
            case "postgresql", "gaussdb", "opengauss" -> 5432;
            case "dameng", "dm" -> 5236;
            case "kingbase" -> 54321;
            case "oceanbase" -> 2881;
            case "tidb" -> 4000;
            default -> 3306;
        };
    }
}
