package com.qqmu.muopt.service;

import com.qqmu.muopt.entity.AiProvider;
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
     * 计算本次请求的输出 token 预算。
     *
     * <p>改写/润色的输出长度与输入 SQL 长度正相关（输出≈改写后的整条 SQL），
     * 因此短 SQL 给 2048 的紧预算（防止豆包、带推理链的模型拿大预算去生成长篇解释，
     * 生成速度 30~60 token/s，一次调用拖过一分钟），长 SQL 按字符数放大下限——
     * SQL 以 ASCII 为主时 token 数远小于字符数，含中文注释时约 1 字符≈1 token，
     * 用字符数做下限是偏安全的估计。用户配置的 Max Tokens 仍是硬上限，
     * 长 SQL 截断时在「AI 模型」页调大即可。
     */
    /**
     * 是否为该模型关闭推理思考链。未显式配置（null，旧数据）时按关闭处理，
     * 与 {@link AiProvider#disableThinking} 的默认值一致；只有显式 false 才保留思考链
     * （适用于希望推理模型深度思考复杂改写的场景）。
     */
    private static boolean thinkingDisabled(AiProvider provider) {
        return !Boolean.FALSE.equals(provider.getDisableThinking());
    }

    private static int outputBudget(String sql, Integer configuredMaxTokens) {
        int configured = configuredMaxTokens == null || configuredMaxTokens <= 0
                ? 4096 : configuredMaxTokens;
        int needed = sql == null ? 2048 : Math.max(2048, sql.length());
        return Math.min(configured, needed);
    }

    /**
     * 系统提示词：角色与输出契约。固定模板放在 system 有两个收益——
     * ① 模型对 system 约束的遵守率高于混在 user 里，输出更少废话（生成 token 少 = 更快）；
     * ② 批量扫描时几十条 SQL 复用同一 system 前缀，网关侧 prompt cache（百炼/DeepSeek 均有）
     *    可命中，prefill 更快更省。
     */
    private static final String SYSTEM_PROMPT = """
            你是资深数据库 SQL 优化专家，熟悉 MySQL、PostgreSQL、Oracle、达梦等主流数据库的索引与执行计划。
            严格遵守以下输出契约：
            1. 只输出优化后的 SQL 文本本身——不要任何解释、不要分析过程、不要 markdown 代码块标记、不要序号标题
            2. 改写必须保持原查询的业务逻辑与返回结果完全一致
            3. 优化方向：相关子查询转 JOIN、小表驱动大表、避免函数或表达式包裹索引列、
               避免隐式类型转换、避免前导 % 的 LIKE、去除冗余排序与冗余 JOIN
            4. 无法安全优化时原样返回输入的 SQL，不要编造改写
            """;

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

        // user 只放任务与待优化 SQL，保持精简
        String userPrompt = "优化以下 SQL：\n" + sql;

        // 预算封顶逻辑见 outputBudget：短 SQL 2048 足够，长 SQL 随输入放大
        int maxTokens = outputBudget(sql, provider.getMaxTokens());
        // 关闭思考链：输出契约本就禁止分析过程，思考链只会吃掉预算导致截断或拖慢超时
        AiChatClient.ChatResult result = chatClient.complete(provider, apiKey, SYSTEM_PROMPT, userPrompt,
                maxTokens, perRequestTimeoutSeconds > 0 ? (long) perRequestTimeoutSeconds : null,
                thinkingDisabled(provider));
        if (!result.isSuccess()) {
            // 外部服务超时/鉴权等属于可预期失败（批量扫描时可能多条同时失败），用 warn 即可。
            // 直接透传端点消息（如“request timed out”“HTTP 401 …”），由上层统一加“AI 优化失败：”前缀
            log.warn("AI 优化 SQL 失败（{}）: {}", provider.getName(), result.getMessage());
            throw new RuntimeException(result.getMessage());
        }
        if (result.isTruncated()) {
            // 截断的 SQL 是半成品，绝不能展示给用户——按失败处理，上层降级本地改写
            log.warn("AI 优化输出达到 token 上限被截断（{}），降级处理", provider.getName());
            throw new RuntimeException("AI 输出超出长度上限被截断，请在「AI 模型」页调大 Max Tokens 后重试");
        }
        String text = stripCodeFence(result.getText()).trim();
        log.info("AI 优化完成: {}（{}）耗时 {}ms，输出 {} 字符",
                provider.getName(), result.effectiveModel(), result.getElapsedMs(), text.length());
        return text;
    }

    // ==================== SQL 转换：方言润色与图片 OCR ====================

    /**
     * 方言润色的系统提示词：角色与输出契约。转换已由规则引擎完成，
     * AI 只做「目标方言适配度 + 性能」的二次把关，不得改变查询语义。
     */
    private static final String POLISH_SYSTEM_PROMPT = """
            你是资深数据库方言迁移专家，熟悉 MySQL、Oracle、SQL Server、DB2、PostgreSQL、MariaDB 与
            达梦、人大金仓、GaussDB、openGauss、瀚高 HighGo、海量 Vastbase、崖山 YashanDB、OceanBase、
            TiDB、GBase、神通、GoldenDB 等国产数据库的语法差异。
            输入是一条已由规则引擎做过初步方言转换的 SQL 及其目标数据库。
            严格遵守以下输出契约：
            1. 只输出润色后的 SQL 文本本身——不要任何解释、不要分析过程、不要 markdown 代码块标记、不要序号标题
            2. 必须保持查询的业务语义与返回结果完全一致
            3. 修正规则转换遗漏或不正确的方言问题（函数、类型、分页、日期格式等），使其符合目标数据库语法
            4. 可顺带做安全的性能改进（如消除明显冗余），但不允许增删查询列与表
            5. 无法确定更优写法时原样返回输入的 SQL，不要编造改写
            """;

    /**
     * 对规则转换后的 SQL 做一轮 AI 方言润色（使用模型自身配置的超时）。
     */
    public String polishConvertedSql(String convertedSql, String targetDb) {
        return polishConvertedSql(convertedSql, targetDb, 0);
    }

    /**
     * 对规则转换后的 SQL 做一轮 AI 方言润色。
     *
     * @param perRequestTimeoutSeconds 批量扫描时的单请求超时上限；&lt;=0 用模型自身配置
     */
    public String polishConvertedSql(String convertedSql, String targetDb, int perRequestTimeoutSeconds) {
        AiProvider provider = providerService.activeProvider()
                .orElseThrow(() -> new IllegalStateException("未启用任何 AI 模型，请先在「AI 模型」页配置并启用"));
        String apiKey = providerService.decryptKey(provider);

        String userPrompt = "目标数据库：" + targetDb + "\n待润色 SQL：\n" + convertedSql;

        // 润色输出长度≈输入长度，预算必须随输入放大，否则长 SQL 必截断
        int maxTokens = outputBudget(convertedSql, provider.getMaxTokens());
        // 关闭思考链：输出契约本就禁止分析过程，思考链只会吃掉预算导致截断或拖慢超时
        AiChatClient.ChatResult result = chatClient.complete(provider, apiKey, POLISH_SYSTEM_PROMPT,
                userPrompt, maxTokens, perRequestTimeoutSeconds > 0 ? (long) perRequestTimeoutSeconds : null,
                thinkingDisabled(provider));
        if (!result.isSuccess()) {
            log.warn("AI 润色 SQL 失败（{}）: {}", provider.getName(), result.getMessage());
            throw new RuntimeException(result.getMessage());
        }
        if (result.isTruncated()) {
            log.warn("AI 润色输出达到 token 上限被截断（{}），降级处理", provider.getName());
            throw new RuntimeException("AI 输出超出长度上限被截断，请在「AI 模型」页调大 Max Tokens 后重试");
        }
        String text = stripCodeFence(result.getText()).trim();
        log.info("AI 润色完成: {}（{}）目标={} 耗时 {}ms",
                provider.getName(), result.effectiveModel(), targetDb, result.getElapsedMs());
        return text;
    }

    /** OCR 识别提示词：只取 SQL，不解释 */
    private static final String OCR_PROMPT =
            "请识别图片中的SQL语句，只返回SQL代码，不要有其他说明文字。如果图片中没有SQL语句，请返回空字符串。";

    /**
     * 图片 OCR 识别 SQL：使用启用模型的视觉模型（visionModel），未配置时抛出可操作的错误。
     */
    public String recognizeImage(byte[] imageData, String mimeType) {
        AiProvider provider = providerService.activeProvider()
                .orElseThrow(() -> new IllegalStateException("未启用任何 AI 模型，请先在「AI 模型」页配置并启用"));
        if (provider.getVisionModel() == null || provider.getVisionModel().isBlank()) {
            throw new IllegalStateException("当前模型「" + provider.getName()
                    + "」未配置视觉模型，无法识别图片，请在「AI 模型」页补充视觉模型（如 qwen-vl-plus）");
        }
        String apiKey = providerService.decryptKey(provider);
        AiChatClient.ChatResult result = chatClient.completeWithImage(provider, apiKey,
                provider.getVisionModel(), imageData, mimeType, OCR_PROMPT, 1024);
        if (!result.isSuccess()) {
            log.warn("图片 OCR 识别失败（{}）: {}", provider.getName(), result.getMessage());
            throw new RuntimeException(result.getMessage());
        }
        String text = stripCodeFence(result.getText()).trim();
        log.info("图片 OCR 完成: {}（{}）耗时 {}ms，识别 {} 字符",
                provider.getName(), result.effectiveModel(), result.getElapsedMs(), text.length());
        return text;
    }

    /**
     * 去除 AI 可能返回的 markdown 代码块标记（转换扫描的 AI 结果安全校验也在用）
     */
    public static String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)```[a-zA-Z]*", "").replace("```", "").trim();
    }
}
