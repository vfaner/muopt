package com.sqloptimizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqloptimizer.entity.AiProtocol;
import com.sqloptimizer.entity.AiProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 向 chat-completion 端点发起一次 HTTP 调用，支持两种协议（移植自 synctool）。
 *
 * <p>连通性探测与正式优化都走这里：探测通过的 URL 必须就是优化实际请求的 URL。
 * 两种协议真正不同的只有三处——认证头、system prompt 的位置、响应文本的位置。
 * 失败以返回值表达而非抛异常：端点自己的错误体（model not found、余额不足）最能说明问题。
 * API Key 只写出站请求头，不打日志、不进结果对象。
 */
@Component
@Slf4j
public class AiChatClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 错误体保留上限，避免堆栈 HTML 页面灌爆 UI */
    private static final int MAX_ERROR_CHARS = 300;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 发送一次补全请求并等待完整回复。
     *
     * @param provider  提供协议、baseUrl、模型、超时
     * @param apiKey    解密后的明文 Key，绝不能传库中密文
     * @param system    系统提示，null 表示不带
     * @param user      用户提示，不可为空白
     * @param maxTokens 回复 token 预算
     * @return 调用结果，不为 null
     */
    public ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                               int maxTokens) {
        AiProtocol protocol = provider.getProtocol() == null ? AiProtocol.OPENAI : provider.getProtocol();
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        long start = System.currentTimeMillis();

        if (model.isEmpty()) {
            return ChatResult.failure("模型不能为空", endpoint, model, 0);
        }

        Duration timeout = Duration.ofSeconds(provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 60 : provider.getTimeoutSeconds());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    // 跨主机重定向会静默丢掉认证头，直接报错让用户配置最终地址
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            HttpRequest request = buildRequest(protocol, endpoint, model, apiKey, timeout,
                    system, user, maxTokens);
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = System.currentTimeMillis() - start;

            if (response.statusCode() / 100 == 2) {
                return ChatResult.success(extractText(protocol, response.body()),
                        servedModel(response.body()), endpoint, model, ms);
            }
            String detail = extractError(response.body());
            log.warn("AI 请求 '{}' 失败: HTTP {} {}",
                    provider.getName(), response.statusCode(), detail);
            return ChatResult.failure("HTTP " + response.statusCode()
                    + (detail.isEmpty() ? "" : " — " + detail), endpoint, model, ms);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatResult.failure("请求被中断", endpoint, model,
                    System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            // 覆盖 DNS 失败、连接拒绝、超时——内网环境最常见的情况
            log.warn("AI 请求 '{}' 失败: {}", provider.getName(), e.toString());
            return ChatResult.failure(e.getMessage() == null ? e.toString() : e.getMessage(),
                    endpoint, model, System.currentTimeMillis() - start);
        }
    }

    private HttpRequest buildRequest(AiProtocol protocol, String endpoint, String model,
                                     String apiKey, Duration timeout,
                                     String system, String user, int maxTokens) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        boolean anthropic = protocol == AiProtocol.ANTHROPIC;
        boolean hasSystem = system != null && !system.isBlank();

        // Anthropic 把 system 作为顶层字段，且拒绝消息列表里出现 system 角色；
        // OpenAI 兼容端点则期望它作为第一条消息。
        if (hasSystem && anthropic) {
            body.put("system", system);
        }
        ArrayNode messages = body.putArray("messages");
        if (hasSystem && !anthropic) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", user);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (anthropic) {
            builder.header("x-api-key", apiKey == null ? "" : apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            // Bearer 形式被各类 OpenAI 兼容端点普遍接受，包括忽略 key 的自部署服务
            builder.header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        }

        return builder.POST(HttpRequest.BodyPublishers.ofString(
                body.toString(), StandardCharsets.UTF_8)).build();
    }

    /** 按协议的响应结构提取回复文本 */
    private String extractText(AiProtocol protocol, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (protocol == AiProtocol.ANTHROPIC) {
                // content 是带类型的 block 列表，只有 text block 承载回复，且首个 block 未必是文本
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : root.path("content")) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText(""));
                    }
                }
                return sb.toString();
            }
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            log.debug("AI 响应体无法按 JSON 解析: {}", e.getMessage());
            return "";
        }
    }

    /** 端点实际应答的模型名，网关常把请求的别名改写为真名 */
    private String servedModel(String responseBody) {
        try {
            return mapper.readTree(responseBody).path("model").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    /** 从错误体提取可读信息，提取不到则截断原文 */
    private String extractError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            for (String path : new String[]{"error", "message"}) {
                JsonNode node = root.path(path);
                if (node.isTextual() && !node.asText().isBlank()) {
                    return truncate(node.asText());
                }
                JsonNode nested = node.path("message");
                if (nested.isTextual() && !nested.asText().isBlank()) {
                    return truncate(nested.asText());
                }
            }
        } catch (IOException e) {
            // 非 JSON：反向代理的 HTML 错误页本身就是有效信号
        }
        return truncate(responseBody);
    }

    private String truncate(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > MAX_ERROR_CHARS
                ? flat.substring(0, MAX_ERROR_CHARS) + "..." : flat;
    }

    /** 一次调用的结果 */
    @Getter
    public static class ChatResult {
        private final boolean success;
        /** 模型回复，失败时为空 */
        private final String text;
        /** 人类可读状态；失败时为端点返回的原始错误 */
        private final String message;
        private final String endpoint;
        /** 响应中声明的应答模型，可能与请求模型不同 */
        private final String servedModel;
        private final String requestedModel;
        private final long elapsedMs;

        private ChatResult(boolean success, String text, String message, String endpoint,
                           String servedModel, String requestedModel, long ms) {
            this.success = success;
            this.text = text;
            this.message = message;
            this.endpoint = endpoint;
            this.servedModel = servedModel;
            this.requestedModel = requestedModel;
            this.elapsedMs = ms;
        }

        static ChatResult success(String text, String servedModel, String endpoint,
                                  String requestedModel, long ms) {
            return new ChatResult(true, text, "OK", endpoint, servedModel, requestedModel, ms);
        }

        static ChatResult failure(String message, String endpoint, String requestedModel, long ms) {
            return new ChatResult(false, "", message, endpoint, "", requestedModel, ms);
        }

        /** 优先用端点声明的模型，未声明时回退到请求模型 */
        public String effectiveModel() {
            return servedModel == null || servedModel.isBlank() ? requestedModel : servedModel;
        }
    }
}
