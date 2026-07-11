package com.sqloptimizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（OpenAI 兼容接口）AI 服务
 * 用于对 SQL 进行深度优化（改写 + 索引建议说明）
 */
@Slf4j
@Service
public class AiService {

    @Value("${ai.api.key}")
    private String apiKey;

    @Value("${ai.api.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String baseUrl;

    @Value("${ai.api.model:qwen-max-latest}")
    private String model;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 是否已配置有效的 API Key
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"YOUR_BAILIAN_API_KEY".equals(apiKey);
    }

    /**
     * AI 深度优化 SQL，返回优化后的 SQL 文本
     */
    public String optimizeSql(String sql) {
        try {
            String prompt = String.format("""
                你是一个资深数据库 SQL 优化专家。请对以下 SQL 进行性能优化改写。

                要求：
                1. 保持原有业务逻辑与返回结果完全一致
                2. 优化点包括但不限于：避免 SELECT *、消除子查询、小表驱动大表、避免隐式类型转换、
                   合理利用索引、去除冗余排序等
                3. 只返回优化后的 SQL 代码本身，不要任何解释文字、不要 markdown 代码块标记

                原始 SQL：
                %s
                """, sql);
            return chat(prompt).trim();
        } catch (Exception e) {
            log.error("AI 优化 SQL 失败", e);
            throw new RuntimeException("AI 优化 SQL 失败: " + e.getMessage());
        }
    }

    private String chat(String prompt) throws IOException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", 4096);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(requestBody),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("百炼 API 调用失败: {} - {}", response.code(), responseBody);
                throw new IOException("百炼 API 调用失败: " + response.code() + " - " + responseBody);
            }
            JsonNode responseJson = objectMapper.readTree(responseBody);
            JsonNode choices = responseJson.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).get("message");
                if (messageNode != null && messageNode.has("content")) {
                    return stripCodeFence(messageNode.get("content").asText());
                }
            }
            throw new IOException("百炼 API 返回格式异常: " + responseBody);
        }
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
