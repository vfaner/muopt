package com.qqmu.muopt.service;

import com.qqmu.muopt.entity.AiProtocol;
import com.qqmu.muopt.entity.AiProvider;
import com.qqmu.muopt.repository.AiProviderRepository;
import com.qqmu.muopt.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * AI 模型配置 CRUD 与“唯一启用”规则。
 *
 * <p>启用一个会在事务内先关掉其他所有启用标志。这放在服务端事务里，而不是浏览器里
 * 禁用按钮：两个打开的页面或过期页面不能各开一个，使 {@link #activeProvider()} 不确定。
 */
@Service
@Slf4j
public class AiProviderService {

    private final AiProviderRepository repository;
    private final AiConnectionTestService testService;
    private final CryptoUtil cryptoUtil;

    public AiProviderService(AiProviderRepository repository,
                             AiConnectionTestService testService,
                             CryptoUtil cryptoUtil) {
        this.repository = repository;
        this.testService = testService;
        this.cryptoUtil = cryptoUtil;
    }

    public List<AiProvider> findAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public Optional<AiProvider> findById(Long id) {
        return repository.findById(id);
    }

    /** SQL 深度优化使用的启用模型；不存在时调用方按“功能不可用”处理 */
    public Optional<AiProvider> activeProvider() {
        return repository.findFirstByEnabledTrue();
    }

    public boolean isAnyActive() {
        return repository.findFirstByEnabledTrue().isPresent();
    }

    /**
     * 新建或更新模型配置。
     *
     * <p>更新时 Key 留空表示“沿用已存储的 Key”，编辑表单无需回传密钥——
     * 与数据库密码同一契约。
     */
    @Transactional
    public AiProvider save(AiProvider provider, String rawApiKey) {
        validate(provider);

        if (provider.getId() != null) {
            AiProvider existing = repository.findById(provider.getId())
                    .orElseThrow(() -> new IllegalArgumentException("AI 模型配置不存在"));
            if (rawApiKey == null || rawApiKey.isEmpty()) {
                provider.setApiKey(existing.getApiKey());
            } else {
                provider.setApiKey(cryptoUtil.encrypt(rawApiKey));
            }
            provider.setCreatedAt(existing.getCreatedAt());
            // 端点变更会使上次探测结论失效，清掉徽章而不是让它继续显示过期的成功
            if (endpointChanged(existing, provider) || (rawApiKey != null && !rawApiKey.isEmpty())) {
                provider.setLastTestAt(null);
                provider.setLastTestOk(null);
                provider.setLastTestMessage(null);
            } else {
                provider.setLastTestAt(existing.getLastTestAt());
                provider.setLastTestOk(existing.getLastTestOk());
                provider.setLastTestMessage(existing.getLastTestMessage());
            }
            provider.setEnabled(existing.isEnabled());
        } else {
            provider.setApiKey(cryptoUtil.encrypt(rawApiKey));
            provider.setEnabled(false);
        }

        AiProvider saved = repository.save(provider);
        log.info("保存 AI 模型配置 '{}' ({} {})", saved.getName(), saved.getProtocol(), saved.getModel());
        return saved;
    }

    private boolean endpointChanged(AiProvider existing, AiProvider updated) {
        return !Objects.equals(existing.getBaseUrl(), updated.getBaseUrl())
                || !Objects.equals(existing.getModel(), updated.getModel())
                || existing.getProtocol() != updated.getProtocol();
    }

    private void validate(AiProvider provider) {
        if (provider.getName() == null || provider.getName().isBlank()) {
            throw new IllegalArgumentException("配置名称不能为空");
        }
        provider.setName(provider.getName().trim());
        repository.findByName(provider.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(provider.getId())) {
                throw new IllegalArgumentException("配置名称已存在: " + provider.getName());
            }
        });

        if (provider.getProtocol() == null) {
            provider.setProtocol(AiProtocol.OPENAI);
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        provider.setBaseUrl(provider.getBaseUrl().trim());
        if (!provider.getBaseUrl().startsWith("http://")
                && !provider.getBaseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("Base URL 必须以 http:// 或 https:// 开头");
        }
        if (provider.getModel() == null || provider.getModel().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        provider.setModel(provider.getModel().trim());
        if (provider.getVisionModel() != null) {
            provider.setVisionModel(provider.getVisionModel().trim());
        }

        if (provider.getMaxTokens() == null || provider.getMaxTokens() <= 0) {
            provider.setMaxTokens(4096);
        }
        if (provider.getTimeoutSeconds() == null || provider.getTimeoutSeconds() <= 0) {
            provider.setTimeoutSeconds(60);
        }
    }

    /**
     * 启用该模型并关闭其他所有模型。
     *
     * <p>随后立即探测一次并记录结果，但探测失败不撤销启用：瞬时网络问题不应让设置无法保存，
     * 列表以红色徽章展示失败。
     */
    @Transactional
    public AiProvider enable(Long id) {
        AiProvider provider = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("AI 模型配置不存在"));
        repository.disableAll();
        // disableAll() 是批量更新，上面的实例可能已过期，重读再置位
        provider = repository.findById(id).orElseThrow();
        provider.setEnabled(true);
        AiProvider saved = repository.save(provider);
        log.info("启用 AI 模型 '{}'，其他模型已自动停用", saved.getName());
        recordProbe(saved);
        return saved;
    }

    @Transactional
    public void disable(Long id) {
        repository.findById(id).ifPresent(provider -> {
            provider.setEnabled(false);
            repository.save(provider);
            log.info("停用 AI 模型 '{}'", provider.getName());
        });
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(provider -> {
            repository.deleteById(id);
            log.info("删除 AI 模型配置 '{}'", provider.getName());
        });
    }

    /** 探测已保存的配置并记录结果，供列表徽章展示 */
    @Transactional
    public AiConnectionTestService.TestResult test(Long id) {
        AiProvider provider = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("AI 模型配置不存在"));
        return recordProbe(provider);
    }

    private AiConnectionTestService.TestResult recordProbe(AiProvider provider) {
        AiConnectionTestService.TestResult result =
                testService.test(provider, decryptKey(provider));
        provider.setLastTestAt(Instant.now());
        provider.setLastTestOk(result.isSuccess());
        provider.setLastTestMessage(abbreviate(result.getMessage()));
        repository.save(provider);
        return result;
    }

    /** 探测尚未保存的表单内容；Key 留空且为编辑场景时回退使用已存储的 Key */
    public AiConnectionTestService.TestResult testTransient(AiProvider provider, String rawApiKey) {
        String key = rawApiKey;
        if ((key == null || key.isEmpty()) && provider.getId() != null) {
            key = repository.findById(provider.getId())
                    .map(this::decryptKey)
                    .orElse(null);
        }
        return testService.test(provider, key);
    }

    /** 解密已存储的 Key；解密失败返回 null，让探测以 401 的形式暴露问题 */
    public String decryptKey(AiProvider provider) {
        try {
            return cryptoUtil.decrypt(provider.getApiKey());
        } catch (IllegalStateException e) {
            log.warn("无法解密 AI 模型 '{}' 的 API Key，请重新填写", provider.getName());
            return null;
        }
    }

    private String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
