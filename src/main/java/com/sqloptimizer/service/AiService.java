package com.sqloptimizer.service;

import com.sqloptimizer.entity.AiProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 服务：使用当前「启用」的 AI 模型配置对 SQL 进行深度优化（改写 + 索引建议说明）。
 *
 * <p>多模型配置与互斥启用见 {@link AiProviderService}；本服务只面向优化场景，
 * 保持 {@link #isConfigured()} / {@link #optimizeSql(String)} 对外契约不变。
 */
@Slf4j
@Service
public class AiService {

    private final AiProviderService providerService;
    private final AiChatClient chatClient;

    public AiService(AiProviderService providerService, AiChatClient chatClient) {
        this.providerService = providerService;
        this.chatClient = chatClient;
    }

    /**
     * 是否存在已启用的 AI 模型
     */
    public boolean isConfigured() {
        return providerService.activeProvider().isPresent();
    }

    /** 当前启用模型的展示名（供状态横幅），未启用返回 null */
    public String getActiveName() {
        return providerService.activeProvider()
                .map(p -> p.getName() + "（" + p.getModel() + "）")
                .orElse(null);
    }

    /**
     * AI 深度优化 SQL，返回优化后的 SQL 文本（使用模型自身配置的超时）
     */
    public String optimizeSql(String sql) {
        return optimizeSql(sql, 0);
    }

    /**
     * AI 深度优化 SQL。
     *
     * @param perRequestTimeoutSeconds 单请求超时上限（秒），用于批量扫描时限流；
     *                                 &lt;=0 表示使用模型自身的超时配置
     */
    public String optimizeSql(String sql, int perRequestTimeoutSeconds) {
        AiProvider provider = providerService.activeProvider()
                .orElseThrow(() -> new IllegalStateException("未启用任何 AI 模型，请先在「AI 模型」页配置并启用"));
        String apiKey = providerService.decryptKey(provider);

        String prompt = """
                你是一个资深数据库 SQL 优化专家。请对以下 SQL 进行性能优化改写。

                要求：
                1. 保持原有业务逻辑与返回结果完全一致
                2. 优化点包括但不限于：避免 SELECT *、消除子查询、小表驱动大表、避免隐式类型转换、
                   合理利用索引、去除冗余排序等
                3. 只返回优化后的 SQL 代码本身，不要任何解释文字、不要 markdown 代码块标记

                原始 SQL：
                %s
                """.formatted(sql);

        int maxTokens = provider.getMaxTokens() == null || provider.getMaxTokens() <= 0
                ? 4096 : provider.getMaxTokens();
        AiChatClient.ChatResult result = chatClient.complete(provider, apiKey, null, prompt,
                maxTokens, perRequestTimeoutSeconds > 0 ? (long) perRequestTimeoutSeconds : null);
        if (!result.isSuccess()) {
            // 外部服务超时/鉴权等属于可预期失败（批量扫描时可能多条同时失败），用 warn 即可。
            // 直接透传端点消息（如“request timed out”“HTTP 401 …”），由上层统一加“AI 优化失败：”前缀
            log.warn("AI 优化 SQL 失败（{}）: {}", provider.getName(), result.getMessage());
            throw new RuntimeException(result.getMessage());
        }
        return stripCodeFence(result.getText()).trim();
    }

    /**
     * 去除 AI 可能返回的 markdown 代码块标记
     */
    private String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)```[a-zA-Z]*", "").replace("```", "").trim();
    }
}
