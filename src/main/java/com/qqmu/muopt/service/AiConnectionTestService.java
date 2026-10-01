package com.qqmu.muopt.service;

import com.qqmu.muopt.entity.AiProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 端点探测：发一个 max_tokens=1 的最小补全请求（移植自 synctool）。
 *
 * <p>TCP 探测对“Key 错、模型名拼错、baseUrl 少一段”这三类最常见错误都会误报成功，
 * 只有真实补全请求能暴露它们，所以探测与正式优化走同一个 {@link AiChatClient}。
 */
@Service
@Slf4j
public class AiConnectionTestService {

    private final AiChatClient client;

    public AiConnectionTestService(AiChatClient client) {
        this.client = client;
    }

    /**
     * @param plainApiKey 解密后的明文 Key，不可传库中密文
     * @return 探测结果，不为 null；失败以结果返回而非抛出，UI 直接展示端点原始错误
     */
    public TestResult test(AiProvider provider, String plainApiKey) {
        AiChatClient.ChatResult result = client.complete(provider, plainApiKey, null, "ping", 1);

        if (!result.isSuccess()) {
            return TestResult.failure(result.getMessage(), result.getEndpoint(),
                    result.getRequestedModel(), result.getElapsedMs());
        }
        log.info("AI 端点探测成功 '{}' ({} at {})",
                provider.getName(), result.effectiveModel(), result.getEndpoint());
        return TestResult.success(describeSuccess(result), result.getEndpoint(),
                result.getRequestedModel(), result.getElapsedMs());
    }

    private String describeSuccess(AiChatClient.ChatResult result) {
        String served = result.getServedModel();
        if (served != null && !served.isBlank() && !served.equals(result.getRequestedModel())) {
            // 网关常静默改写模型别名，应答模型名值得回显
            return "OK — 实际应答模型: " + served;
        }
        return "OK";
    }

    /** 探测结果 */
    @Getter
    public static class TestResult {
        private final boolean success;
        private final String message;
        private final String endpoint;
        private final String model;
        private final long elapsedMs;

        private TestResult(boolean success, String message, String endpoint, String model, long ms) {
            this.success = success;
            this.message = message;
            this.endpoint = endpoint;
            this.model = model;
            this.elapsedMs = ms;
        }

        static TestResult success(String message, String endpoint, String model, long ms) {
            return new TestResult(true, message, endpoint, model, ms);
        }

        static TestResult failure(String message, String endpoint, String model, long ms) {
            return new TestResult(false, message, endpoint, model, ms);
        }
    }
}
