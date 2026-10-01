package com.qqmu.muopt.entity;

import lombok.Getter;

/**
 * AI 端点的接线协议（移植自 synctool）。
 *
 * <p>国产/自部署推理服务基本都讲 OpenAI chat-completions 格式，{@link #OPENAI} 同时表示
 * “OpenAI 兼容”（百炼、DeepSeek、内网网关等）。{@code chatPath} 仅在 baseUrl 未以该
 * 路径结尾时追加，因为各家对 /v1 属于 baseUrl 还是 path 并不一致。
 */
@Getter
public enum AiProtocol {

    OPENAI("OpenAI 兼容", "https://dashscope.aliyuncs.com/compatible-mode/v1", "/chat/completions"),

    ANTHROPIC("Anthropic", "https://api.anthropic.com", "/v1/messages");

    private final String displayName;

    /** 选择协议时预填，绝大多数情况会被替换 */
    private final String defaultBaseUrl;

    private final String chatPath;

    AiProtocol(String displayName, String defaultBaseUrl, String chatPath) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.chatPath = chatPath;
    }

    public static AiProtocol fromName(String name) {
        if (name != null) {
            for (AiProtocol p : values()) {
                if (p.name().equalsIgnoreCase(name.trim())) {
                    return p;
                }
            }
        }
        return OPENAI;
    }

    /** 实际请求的完整地址；在测试结果中原样回显，便于发现 404/重复 /v1 等配置错误 */
    public String resolveEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = defaultBaseUrl;
        }
        return base.endsWith(chatPath) ? base : base + chatPath;
    }
}
