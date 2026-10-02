package com.qqmu.muopt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.muopt.entity.AiProtocol;
import com.qqmu.muopt.entity.AiProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

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
     * 访问 AI 端点使用的 HTTP 代理（如 http://127.0.0.1:7890）。
     * 为空时自动回退标准环境变量 HTTPS_PROXY / HTTP_PROXY；本机地址不走代理。
     * 公司内网直连外网超时时，这通常就是根因——JDK 默认不使用系统代理。
     */
    @org.springframework.beans.factory.annotation.Value("${app.ai.proxy:}")
    private String configuredProxy;

    /** 环境变量代理候选（顺序即优先级） */
    private static final List<String> PROXY_ENVS =
            List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy");

    private static final List<String> NO_PROXY_HOSTS =
            List.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

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
        return complete(provider, apiKey, system, user, maxTokens, null);
    }

    /**
     * 发送一次补全请求并等待完整回复。
     *
     * @param overrideTimeoutSeconds 批量场景下的单请求超时上限（秒）；
     *                               与模型自身超时取较小值，null/<=0 表示只用模型配置
     */
    public ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                               int maxTokens, Long overrideTimeoutSeconds) {
        return complete(provider, apiKey, system, user, maxTokens, overrideTimeoutSeconds, false);
    }

    /**
     * 发送一次补全请求并等待完整回复。
     *
     * @param thinkingOff SQL 改写 / 方言润色类任务置 true：关闭推理模型的思考链，
     *                    使 {@code max_tokens} 全部留给最终输出，避免思考链吃掉预算导致 SQL
     *                    被截断（finish_reason=length）或思考过长拖慢到超时。
     */
    public ChatResult complete(AiProvider provider, String apiKey, String system, String user,
                               int maxTokens, Long overrideTimeoutSeconds, boolean thinkingOff) {
        AiProtocol protocol = provider.getProtocol() == null ? AiProtocol.OPENAI : provider.getProtocol();
        String model = provider.getModel() == null ? "" : provider.getModel().trim();
        return execute(provider, apiKey, model,
                buildTextBody(protocol, model, system, user, maxTokens, thinkingOff), overrideTimeoutSeconds);
    }

    /**
     * 发送一次多模态（图片 + 文本）请求，用于 OCR 识别截图中的 SQL。
     * model 由调用方指定（视觉模型常与文本模型不同），空串时回退到配置的主模型。
     */
    public ChatResult completeWithImage(AiProvider provider, String apiKey, String model,
                                        byte[] imageData, String mimeType, String prompt, int maxTokens) {
        AiProtocol protocol = provider.getProtocol() == null ? AiProtocol.OPENAI : provider.getProtocol();
        String effective = model == null || model.isBlank()
                ? (provider.getModel() == null ? "" : provider.getModel().trim()) : model.trim();
        return execute(provider, apiKey, effective,
                buildVisionBody(protocol, effective, imageData, mimeType, prompt, maxTokens), null);
    }

    /** 发送已构建好的请求体并解析响应；协议差异在 body 构建阶段已抹平 */
    private ChatResult execute(AiProvider provider, String apiKey, String model, ObjectNode body,
                               Long overrideTimeoutSeconds) {
        AiProtocol protocol = provider.getProtocol() == null ? AiProtocol.OPENAI : provider.getProtocol();
        String endpoint = protocol.resolveEndpoint(provider.getBaseUrl());
        long start = System.currentTimeMillis();

        if (model.isEmpty()) {
            return ChatResult.failure("模型不能为空", endpoint, model, 0);
        }

        long configured = provider.getTimeoutSeconds() == null
                || provider.getTimeoutSeconds() <= 0 ? 60 : provider.getTimeoutSeconds();
        long effectiveSeconds = overrideTimeoutSeconds != null && overrideTimeoutSeconds > 0
                ? Math.min(configured, overrideTimeoutSeconds) : configured;
        // 请求超时（等响应）与连接超时分开：连不上时不应占用一个批量名额几十秒
        Duration requestTimeout = Duration.ofSeconds(effectiveSeconds);
        Duration connectTimeout = Duration.ofSeconds(Math.min(10, effectiveSeconds));

        String host = URI.create(endpoint).getHost();
        try {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    // 跨主机重定向会静默丢掉认证头，直接报错让用户配置最终地址
                    .followRedirects(HttpClient.Redirect.NEVER);
            ProxySelector proxy = proxySelectorFor(URI.create(endpoint));
            if (proxy != null) {
                builder.proxy(proxy);
            }
            HttpClient client = builder.build();

            HttpRequest request = toRequest(protocol, endpoint, apiKey, requestTimeout, body);
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = System.currentTimeMillis() - start;

            if (response.statusCode() / 100 == 2) {
                return ChatResult.success(extractText(protocol, response.body()),
                        servedModel(response.body()), endpoint, model, ms,
                        isTruncated(protocol, response.body()));
            }
            String detail = extractError(response.body());
            log.warn("AI 请求 '{}' 失败: HTTP {} {}",
                    provider.getName(), response.statusCode(), detail);
            return ChatResult.failure("HTTP " + response.statusCode()
                    + (detail.isEmpty() ? "" : " — " + detail), endpoint, model, ms);

        } catch (HttpConnectTimeoutException e) {
            // 连接阶段超时：TCP 都握不上手——内网直连外网不通、需要代理时最典型的表现
            String msg = String.format("连接 AI 端点超时：%d 秒内无法连接 %s。通常是网络不可达；"
                            + "若处于公司内网，可能需要配置 HTTP 代理（设置 app.ai.proxy 或环境变量 HTTPS_PROXY）。",
                    connectTimeout.toSeconds(), host);
            log.warn("AI 请求 '{}' 连接超时: {}", provider.getName(), host);
            return ChatResult.failure(msg, endpoint, model, System.currentTimeMillis() - start);
        } catch (HttpTimeoutException e) {
            // 已连通但模型迟迟不响应：模型慢、超时设置过小或路径/模型名错误被网关挂起
            String msg = String.format("AI 模型 %d 秒内未返回响应（连接已建立）：模型繁忙或超时时间过短，"
                            + "可在「AI 模型」页调大超时后重试；火山方舟请确认 Base URL 为 "
                            + "https://ark.cn-beijing.volces.com/api/v3，且模型填写接入点 ID（ep-xxxx）。",
                    requestTimeout.toSeconds());
            log.warn("AI 请求 '{}' 响应超时: {}", provider.getName(), host);
            return ChatResult.failure(msg, endpoint, model, System.currentTimeMillis() - start);
        } catch (UnknownHostException e) {
            return ChatResult.failure("无法解析 AI 端点域名「" + host
                            + "」（DNS 失败），请检查 Base URL 是否正确、本机网络是否需要代理。",
                    endpoint, model, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatResult.failure("请求被中断", endpoint, model,
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            // 覆盖连接拒绝、SSL 握手失败、连接重置等——内网环境常见。
            // JDK 客户端常只给异常类名而把细节放在 cause 里，统一沿 cause 链取信息。
            String detail = rootMessage(e);
            String msg = "无法连接 AI 端点（" + e.getClass().getSimpleName()
                    + (detail.isEmpty() ? "" : "：" + detail)
                    + "）。请确认 Base URL 与端口可访问；公司内网直连外网受限时，"
                    + "可配置环境变量 HTTPS_PROXY（或 app.ai.proxy）后重启。";
            log.warn("AI 请求 '{}' 连接失败: {}", provider.getName(), e.toString());
            return ChatResult.failure(msg, endpoint, model, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            // 响应解析失败等
            log.warn("AI 请求 '{}' 失败: {}", provider.getName(), e.toString());
            return ChatResult.failure(e.getMessage() == null ? e.toString() : e.getMessage(),
                    endpoint, model, System.currentTimeMillis() - start);
        }
    }

    /** 沿 cause 链取最后一个有意义的消息；全部为空时返回空串（不回退类名，由调用方补充） */
    private static String rootMessage(Throwable e) {
        String msg = "";
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().isBlank()
                    && !c.getMessage().equals(c.getClass().getName())) {
                msg = c.getMessage();
            }
        }
        return msg;
    }

    /**
     * 按目标端点解析代理：本机地址直连；否则取 app.ai.proxy 配置，
     * 再回退标准环境变量 HTTPS_PROXY / HTTP_PROXY。无需代理时返回 null。
     */
    private ProxySelector proxySelectorFor(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null || NO_PROXY_HOSTS.contains(host.toLowerCase())
                || host.startsWith("127.") || host.equals("[::1]")) {
            return null;
        }
        String spec = configuredProxy;
        if (spec == null || spec.isBlank()) {
            for (String name : PROXY_ENVS) {
                String v = System.getenv(name);
                if (v != null && !v.isBlank()) {
                    spec = v;
                    break;
                }
            }
        }
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            URI proxyUri = URI.create(spec.contains("://") ? spec : "http://" + spec);
            if (proxyUri.getHost() == null || proxyUri.getPort() < 0) {
                log.warn("AI 代理配置无效（需形如 http://host:port）: {}", spec);
                return null;
            }
            if (proxyUri.getUserInfo() != null) {
                log.warn("AI 代理地址中包含账号密码，当前暂不支持代理认证，已忽略凭据部分");
            }
            // 代理慢或代理软件没开时，每次 AI 请求都会先卡连接阶段——升 info 便于排查「为什么慢」
            log.info("AI 请求 {} 走代理 {}:{}", host, proxyUri.getHost(), proxyUri.getPort());
            return ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort()));
        } catch (RuntimeException e) {
            log.warn("AI 代理配置无效 {}: {}", spec, e.getMessage());
            return null;
        }
    }

    /**
     * 纯文本对话请求体。
     * Anthropic 把 system 作为顶层字段，且拒绝消息列表里出现 system 角色；
     * OpenAI 兼容端点则期望它作为第一条消息。
     */
    private ObjectNode buildTextBody(AiProtocol protocol, String model,
                                     String system, String user, int maxTokens, boolean thinkingOff) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (thinkingOff) {
            applyDisabledThinking(body, protocol);
        }

        boolean anthropic = protocol == AiProtocol.ANTHROPIC;
        boolean hasSystem = system != null && !system.isBlank();

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
        return body;
    }

    /**
     * 关闭推理模型的思考链。输出契约（只回 SQL、不要分析过程）本就排斥思考链，
     * 关掉后 max_tokens 全额留给最终输出，从根上消除「思考链吃掉预算导致截断 / 拖慢超时」。
     *
     * <p>Anthropic 的 {@code thinking:{type:"disabled"}} 是官方字段，兼容端点普遍支持；
     * OpenAI 兼容端点无统一参数，同时下发 {@code enable_thinking:false}（Qwen/GLM/Kimi 等）
     * 与 {@code thinking:{type:"disabled"}}（火山方舟），两者都不认识的端点会静默忽略未知字段，
     * 不影响非推理模型正常作答。
     */
    private void applyDisabledThinking(ObjectNode body, AiProtocol protocol) {
        ObjectNode thinking = body.putObject("thinking");
        thinking.put("type", "disabled");
        if (protocol != AiProtocol.ANTHROPIC) {
            body.put("enable_thinking", false);
        }
    }

    /** 图片 + 文本请求体：OpenAI 兼容用 image_url data-url，Anthropic 用 base64 source 块 */
    private ObjectNode buildVisionBody(AiProtocol protocol, String model, byte[] imageData,
                                       String mimeType, String prompt, int maxTokens) {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageData);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");

        if (protocol == AiProtocol.ANTHROPIC) {
            ObjectNode image = content.addObject();
            image.put("type", "image");
            ObjectNode source = image.putObject("source");
            source.put("type", "base64");
            source.put("media_type", mimeType);
            source.put("data", base64);
        } else {
            ObjectNode image = content.addObject();
            image.put("type", "image_url");
            image.putObject("image_url").put("url", "data:" + mimeType + ";base64," + base64);
        }
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", prompt);
        return body;
    }

    private HttpRequest toRequest(AiProtocol protocol, String endpoint, String apiKey,
                                  Duration timeout, ObjectNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (protocol == AiProtocol.ANTHROPIC) {
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

    /**
     * 输出是否因 token 预算耗尽被截断（OpenAI: finish_reason=length；
     * Anthropic: stop_reason=max_tokens）。截断的 SQL 是半成品，上层应降级而非直接使用。
     */
    private boolean isTruncated(AiProtocol protocol, String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (protocol == AiProtocol.ANTHROPIC) {
                return "max_tokens".equals(root.path("stop_reason").asText(""));
            }
            return "length".equals(root.path("choices").path(0).path("finish_reason").asText(""));
        } catch (IOException e) {
            return false;
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
        /** 输出是否因 token 预算耗尽被截断 */
        private final boolean truncated;

        private ChatResult(boolean success, String text, String message, String endpoint,
                           String servedModel, String requestedModel, long ms, boolean truncated) {
            this.success = success;
            this.text = text;
            this.message = message;
            this.endpoint = endpoint;
            this.servedModel = servedModel;
            this.requestedModel = requestedModel;
            this.elapsedMs = ms;
            this.truncated = truncated;
        }

        static ChatResult success(String text, String servedModel, String endpoint,
                                  String requestedModel, long ms, boolean truncated) {
            return new ChatResult(true, text, "OK", endpoint, servedModel, requestedModel, ms, truncated);
        }

        static ChatResult failure(String message, String endpoint, String requestedModel, long ms) {
            return new ChatResult(false, "", message, endpoint, "", requestedModel, ms, false);
        }

        /** 优先用端点声明的模型，未声明时回退到请求模型 */
        public String effectiveModel() {
            return servedModel == null || servedModel.isBlank() ? requestedModel : servedModel;
        }
    }
}
