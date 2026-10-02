package com.qqmu.muopt.service;

import com.qqmu.muopt.common.OptimizeResult;
import com.qqmu.muopt.common.ScanItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 项目扫描服务
 * 遍历指定目录，从以下来源提取 SQL：
 * - MyBatis XML 映射文件：<select>/<insert>/<update>/<delete> 标签体
 * - Java 源码：以 SELECT/INSERT/UPDATE/DELETE 开头的字符串字面量（排除 selectDemo() 这类方法调用）
 * - 独立 .sql 脚本文件
 * 记录文件路径与行号，逐条调用 SqlOptimizerService 产出优化结果，并支持原地替换。
 */
@Slf4j
@Service
public class ProjectScanService {

    private final SqlOptimizerService optimizerService;
    private final AiService aiService;
    private final LocalRewriteService localRewrite;

    /** 需要跳过的目录名 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", "target", "build", "dist", "node_modules", ".gradle", "out", "logs");

    /** SQL 起始关键字（判定字符串字面量是否为 SQL） */
    private static final Pattern SQL_START = Pattern.compile(
            "^\\s*(SELECT|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\b", Pattern.CASE_INSENSITIVE);

    /** Java 字符串字面量（不处理转义换行外的多行拼接） */
    private static final Pattern JAVA_STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    /** SQL 相关注解起始：@Select / @Insert / @Update / @Delete（MyBatis）、@Query（JPA） */
    private static final Pattern SQL_ANNOTATION = Pattern.compile(
            "@(Select|Insert|Update|Delete|Query)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** MyBatis 增删改查标签体 */
    private static final Pattern MYBATIS_TAG = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** MyBatis 动态标签（含这些则不可安全替换） */
    private static final Pattern MYBATIS_DYNAMIC = Pattern.compile(
            "<(if|choose|when|otherwise|foreach|where|set|trim|bind|include)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 参数占位符：MyBatis 的 #{...} 与 ${...} */
    private static final Pattern SQL_PLACEHOLDER = Pattern.compile("[#$]\\{[^}]*}");

    /** 单次扫描允许的 AI 调用条数上限（远程调用不设上限会把请求挂死） */
    private static final int MAX_AI_CALLS_PER_SCAN = 20;

    /** AI 调用并发度（app.ai.scan-concurrency 可覆盖） */
    @org.springframework.beans.factory.annotation.Value("${app.ai.scan-concurrency:8}")
    private int scanConcurrency;

    /** 一次扫描中 AI 部分的最长总等待秒数，超时未完成的条目保留本地分析（app.ai.scan-timeout-seconds 可覆盖） */
    @org.springframework.beans.factory.annotation.Value("${app.ai.scan-timeout-seconds:120}")
    private int scanTimeoutSeconds;

    /**
     * 批量扫描时单条 AI 请求的超时上限（秒）：与模型自身超时取较小值。
     * 挂死的请求尽早释放并发名额给后面的 SQL；手工优化不受此限制。
     */
    @org.springframework.beans.factory.annotation.Value("${app.ai.scan-per-request-timeout-seconds:45}")
    private int scanPerRequestTimeoutSeconds;

    /**
     * 本进程内已扫描过的项目根目录（规范化绝对路径）。
     * 替换只允许作用于这些目录内的文件，防止请求方指定任意路径写入。
     */
    private final Set<String> scannedRoots = ConcurrentHashMap.newKeySet();

    @Autowired
    public ProjectScanService(SqlOptimizerService optimizerService, AiService aiService,
                              LocalRewriteService localRewrite) {
        this.optimizerService = optimizerService;
        this.aiService = aiService;
        this.localRewrite = localRewrite;
    }

    /**
     * 扫描项目目录，返回所有识别出的 SQL 及优化结果
     *
     * @param projectPath 项目根目录
     * @param enableAi    是否启用 AI 深度优化
     */
    public List<ScanItem> scan(String projectPath, boolean enableAi) {
        Path root = Paths.get(projectPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("目录不存在或不是文件夹: " + projectPath);
        }
        // 登记为可替换根目录（replace 只允许改动这些目录内的文件）
        scannedRoots.add(canonical(root));

        List<ScanItem> items = new ArrayList<>();
        int[] counter = {0};

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isInSkippedDir(root, p))
                    .toList();
            for (Path file : files) {
                String name = file.getFileName().toString().toLowerCase();
                try {
                    if (name.endsWith(".xml")) {
                        extractFromXml(root, file, items, counter);
                    } else if (name.endsWith(".java")) {
                        extractFromJava(root, file, items, counter);
                    } else if (name.endsWith(".sql")) {
                        extractFromSqlFile(root, file, items, counter);
                    }
                } catch (Exception e) {
                    log.warn("扫描文件失败 {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("遍历项目目录失败: " + e.getMessage(), e);
        }

        // 1) 全部条目先做本地规则分析（毫秒级），保证任何情况下每条都有完整结果，
        //    AI 慢/超时也不影响索引建议、冗余索引、大小表等分析的展示。
        for (ScanItem item : items) {
            fillLocalResult(item);
        }
        if (!enableAi) {
            return items;
        }

        // 2) 未启用模型：尝试本地规则改写，没有可安全改写的写法时给引导提示
        if (!aiService.isConfigured()) {
            for (ScanItem item : items) {
                if (!applyLocalFallback(item, "未启用 AI 模型")) {
                    item.getTips().add("未启用 AI 模型，已跳过 AI 深度优化（请在「AI 模型」页配置并启用一个模型）。");
                }
            }
            return items;
        }

        // 3) AI 改写：限并发、限条数、限总时长。远程调用串行执行时每条最长可等
        //    timeoutSeconds，几十条会让页面无限转圈；并发执行 + 总时限到点即返回，
        //    未完成的条目保留本地分析并附提示。
        if (items.size() > MAX_AI_CALLS_PER_SCAN) {
            log.warn("扫描出 {} 条 SQL，超过单次 AI 优化上限 {}，其余条目只做本地规则分析",
                    items.size(), MAX_AI_CALLS_PER_SCAN);
            for (int i = MAX_AI_CALLS_PER_SCAN; i < items.size(); i++) {
                items.get(i).getTips().add("已达单次扫描的 AI 优化上限（" + MAX_AI_CALLS_PER_SCAN
                        + " 条），该条仅做本地规则分析。可缩小扫描范围后重试。");
            }
        }
        runAiWithDeadline(items.subList(0, Math.min(items.size(), MAX_AI_CALLS_PER_SCAN)));
        return items;
    }

    /** 本地规则分析（不调用 AI），异常时退化为原文 + 失败提示 */
    private void fillLocalResult(ScanItem item) {
        try {
            OptimizeResult r = optimizerService.optimize(item.getSourceSql(), false);
            item.setOptimizedSql(r.getOptimizedSql());
            item.setIndexSuggestions(r.getIndexSuggestions());
            item.getTips().addAll(r.getTips());
        } catch (Exception e) {
            item.setOptimizedSql(item.getSourceSql());
            item.getTips().add("该条 SQL 本地分析失败：" + e.getMessage());
        }
    }

    /**
     * 并发执行 AI 改写，受总时限约束。到点未完成的任务被中断取消，
     * 对应条目保留本地分析结果并附超时提示。
     */
    private void runAiWithDeadline(List<ScanItem> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        int concurrency = Math.max(1, Math.min(scanConcurrency, candidates.size()));
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "scan-ai-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, tf);
        List<CompletableFuture<Void>> futures = candidates.stream()
                .map(item -> CompletableFuture.runAsync(() -> applyAi(item), pool))
                .toList();
        boolean deadlineExceeded = false;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(scanTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            deadlineExceeded = true;
            futures.forEach(f -> f.cancel(true));
            log.warn("AI 批量优化超过总时限 {}s，未完成的条目仅保留本地分析", scanTimeoutSeconds);
        } catch (InterruptedException e) {
            // 扫描任务被取消（用户在页面上点了取消）：恢复中断标志并向上抛出，
            // 由 ScanJobManager 把任务标记为 CANCELLED，而不是当成正常完成
            Thread.currentThread().interrupt();
            throw new RuntimeException("扫描已被取消", e);
        } catch (Exception e) {
            log.warn("AI 批量优化等待异常: {}", e.getMessage());
        } finally {
            pool.shutdownNow();
            try {
                // 给被取消的 HTTP 请求一点时间抛出中断异常，避免与超时提示重复
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.debug("仍有 AI 任务未在中断后 5s 内退出");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (deadlineExceeded) {
            for (ScanItem item : candidates) {
                if (item.isAiOptimized()) {
                    continue;
                }
                // 已在单条任务内处理完（失败/空返回并降级）的条目不再补超时提示，
                // 只有被总时限中断、什么结论都没拿到的条目才提示超时
                boolean alreadyHandled = item.getTips().stream()
                        .anyMatch(t -> t.startsWith("AI 优化失败") || t.startsWith("AI 返回内容为空"));
                if (!alreadyHandled) {
                    // 被总时限中断的条目：尝试本地规则改写，无可改写写法时给出保留本地分析的提示
                    String timeoutReason = "AI 优化超过本次扫描总时限（" + scanTimeoutSeconds + " 秒）未返回";
                    boolean rewritten = item.getOptimizedSql() != null
                            && !item.getOptimizedSql().equals(item.getSourceSql());
                    if (!rewritten) {
                        rewritten = applyLocalFallback(item, timeoutReason);
                    }
                    if (!rewritten) {
                        item.getTips().add(timeoutReason + "，本地规则未发现可安全改写的写法，"
                                + "该条保留本地分析结果，可稍后单独重试 AI。");
                    }
                }
            }
        }
        long ok = candidates.stream().filter(ScanItem::isAiOptimized).count();
        log.info("AI 批量改写完成：{}/{} 条成功（并发 {}，总时限 {}s）",
                ok, candidates.size(), concurrency, scanTimeoutSeconds);
    }

    /** 单条 AI 改写；中断（总时限到）时不加失败提示，由总流程补超时提示并统一降级 */
    private void applyAi(ScanItem item) {
        try {
            // 批量场景给单请求套上比总时限更短的上限，避免个别挂死请求占住并发名额
            String optimized = aiService.optimizeSql(item.getSourceSql(), scanPerRequestTimeoutSeconds);
            if (Thread.currentThread().isInterrupted()) {
                return; // 总时限收尾时统一补提示与本地降级
            }
            if (optimized != null && !optimized.isBlank()) {
                item.setOptimizedSql(optimized);
                item.setAiOptimized(true);
                refillAnalysisAfterAi(item);
            } else if (!applyLocalFallback(item, "AI 返回内容为空")) {
                item.getTips().add("AI 返回内容为空，该条保留本地分析结果，可稍后重试。");
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return; // 总时限收尾时统一补提示与本地降级
            }
            String reason = e.getMessage() == null ? "AI 服务不可用" : e.getMessage();
            if (!applyLocalFallback(item, "AI 优化失败：" + reason)) {
                item.getTips().add("AI 优化失败：" + reason
                        + "；本地规则未发现可安全改写的写法，该条保留本地分析结果，可稍后重试。");
            }
        }
    }

    /**
     * AI 改写成功后，基于改写后的 SQL 重算索引建议与提示（本地分析 + 数据源核实，
     * 毫秒~秒级），并整体替换第 ① 步针对原 SQL 生成的建议与提示——
     * 保证展示的建议、小表驱动、基础提示全部对应用户最终看到的 SQL，
     * 与手工优化的流水线语义一致。失败时保留改写结果与旧建议，并在提示中说明。
     */
    private void refillAnalysisAfterAi(ScanItem item) {
        try {
            OptimizeResult r = optimizerService.optimize(item.getOptimizedSql(), false);
            item.setOptimizedSql(r.getOptimizedSql());
            item.setIndexSuggestions(r.getIndexSuggestions());
            item.setTips(new ArrayList<>(r.getTips()));
        } catch (Exception e) {
            log.debug("AI 改写后重算本地分析失败: {}", e.getMessage());
            item.getTips().add("基于改写后 SQL 的索引分析失败（" + e.getMessage()
                    + "），以下索引建议仍对应原始 SQL。");
        }
    }

    /**
     * AI 不可用（失败/超时）时的降级路径：用本地规则改写 SQL。
     * 已产生过改写的条目不重复应用。返回是否发生改写。
     */
    private boolean applyLocalFallback(ScanItem item, String reason) {
        if (item.isAiOptimized()) {
            return false;
        }
        // 去掉错误信息末尾的句号，避免与拼接的「，已自动…」连用
        reason = reason.replaceAll("[。.；;，,\\s]+$", "");
        LocalRewriteService.RewriteOutcome outcome = localRewrite.tryRewrite(item.getSourceSql());
        if (outcome == null) {
            return false;
        }
        item.setOptimizedSql(outcome.getRewrittenSql());
        item.getTips().add(reason + "，已自动改用本地规则改写：");
        outcome.getChanges().forEach(c -> item.getTips().add("  · " + c));
        return true;
    }

    // ---------- XML（MyBatis） ----------

    private void extractFromXml(Path root, Path file, List<ScanItem> items, int[] counter) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 仅处理疑似 MyBatis 映射文件
        if (!content.contains("<mapper") && !MYBATIS_TAG.matcher(content).find()) {
            return;
        }
        Matcher m = MYBATIS_TAG.matcher(content);
        while (m.find()) {
            String body = m.group(2);
            String sql = cleanMybatisBody(body);
            if (!looksLikeSql(sql)) {
                continue;
            }
            ScanItem item = newItem(root, file, content, m.start(2), body, sql, "MYBATIS_XML", counter);
            // 含动态标签时，标记为不可安全整体替换，仅供参考
            if (MYBATIS_DYNAMIC.matcher(body).find()) {
                item.getTips().add("包含 MyBatis 动态标签，无法安全整体替换，仅供参考");
            }
            if (!item.getPlaceholders().isEmpty()) {
                item.getTips().add("原文含参数占位符 " + String.join("、", item.getPlaceholders())
                        + "，展示的 SQL 中已替换为 ? 便于解析。回写时必须把占位符原样写回，"
                        + "否则参数绑定会变成硬编码常量——替换前请在优化结果中改回占位符。");
            }
            items.add(item);
        }
    }

    /**
     * 清洗 MyBatis 标签体：去 CDATA、去注释，将 #{}/${} 占位符替换为可解析的占位常量。
     */
    private String cleanMybatisBody(String body) {
        String s = body;
        s = s.replaceAll("<!\\[CDATA\\[", "").replaceAll("]]>", "");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        // 移除动态标签本身（保留其文本内容），便于粗略解析
        s = s.replaceAll("(?s)<[^>]+>", " ");
        // MyBatis 占位符替换为字面量，避免解析失败
        s = s.replaceAll("#\\{[^}]*}", "?");
        s = s.replaceAll("\\$\\{[^}]*}", "col_x");
        return s.replaceAll("\\s+", " ").trim();
    }

    // ---------- Java 源码字符串 ----------

    private void extractFromJava(Path root, Path file, List<ScanItem> items, int[] counter) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 记录已被注解 SQL 消费的字符区间，避免普通字符串扫描重复抓取
        List<int[]> consumed = new ArrayList<>();

        // 1. 注解里的 SQL：@Select / @Insert / @Update / @Delete / @Query
        extractFromAnnotations(root, file, content, items, counter, consumed);

        // 2. 普通字符串字面量里的 SQL（排除已被注解消费的区间；方法调用如 selectDemo() 不在字符串内，天然排除）
        Matcher m = JAVA_STRING.matcher(content);
        while (m.find()) {
            if (isConsumed(consumed, m.start())) {
                continue;
            }
            String literal = m.group(1);
            String sql = normalizeJavaLiteral(literal);
            if (!SQL_START.matcher(sql).find() || !looksLikeSql(sql)) {
                continue;
            }
            // rawText 用带引号的完整字面量，替换时精确定位
            String raw = "\"" + literal + "\"";
            ScanItem item = newItem(root, file, content, m.start(), raw, sql, "JAVA_STRING", counter);
            items.add(item);
        }
    }

    /**
     * 提取 SQL 注解中的语句。支持三种写法：
     * - 单串：@Select("SELECT ...")
     * - 拼接：@Select("SELECT ... " + "FROM ...")
     * - 数组：@Select({"SELECT ...", "FROM ..."})
     * rawText 取注解括号内的完整实参文本，便于整体替换为单条字符串。
     */
    private void extractFromAnnotations(Path root, Path file, String content,
                                        List<ScanItem> items, int[] counter, List<int[]> consumed) {
        Matcher m = SQL_ANNOTATION.matcher(content);
        while (m.find()) {
            int open = m.end() - 1; // 指向 '('
            int close = matchParen(content, open);
            if (close < 0) {
                continue;
            }
            String argText = content.substring(open + 1, close); // 括号内实参
            String sql = joinStringLiterals(argText);
            if (sql == null || !SQL_START.matcher(sql).find() || !looksLikeSql(sql)) {
                continue;
            }
            // rawText 为括号内实参原文，替换时整体换为单条字符串字面量
            ScanItem item = newItem(root, file, content, open + 1, argText, sql, "JAVA_ANNOTATION", counter);
            items.add(item);
            consumed.add(new int[]{open, close});
        }
    }

    /**
     * 将注解实参中的所有字符串字面量拼接为一条 SQL（忽略 {} 数组括号、+ 拼接符与其他 token）。
     */
    private String joinStringLiterals(String argText) {
        Matcher sm = JAVA_STRING.matcher(argText);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (sm.find()) {
            found = true;
            sb.append(normalizeJavaLiteral(sm.group(1))).append(' ');
        }
        if (!found) {
            return null;
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** 反转义 Java 字符串字面量并压缩空白 */
    private String normalizeJavaLiteral(String literal) {
        String unescaped = literal.replace("\\n", " ").replace("\\t", " ")
                .replace("\\\"", "\"").replace("\\\\", "\\");
        return unescaped.replaceAll("\\s+", " ").trim();
    }

    /** 从 openIdx（'('）开始找到匹配的 ')'，考虑字符串字面量内的括号。找不到返回 -1 */
    private int matchParen(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++; // 跳过转义字符
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean isConsumed(List<int[]> consumed, int pos) {
        for (int[] range : consumed) {
            if (pos >= range[0] && pos <= range[1]) {
                return true;
            }
        }
        return false;
    }

    // ---------- 独立 .sql 文件 ----------

    private void extractFromSqlFile(Path root, Path file, List<ScanItem> items, int[] counter) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 在原文上按顶层分号切分（跳过注释与字符串字面量中的分号），
        // 这样 rawText 能与文件原文逐字一致，行号与替换定位才准确。
        int segStart = 0;
        boolean inSingle = false, inDouble = false, inLineComment = false, inBlockComment = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
            } else if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
            } else if (inSingle) {
                if (c == '\'') inSingle = false;
            } else if (inDouble) {
                if (c == '"') inDouble = false;
            } else if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == ';') {
                addSqlSegment(root, file, content, segStart, i, items, counter);
                segStart = i + 1;
            }
        }
        addSqlSegment(root, file, content, segStart, content.length(), items, counter);
    }

    /**
     * 把 [from, to) 区间当作一条 SQL 收录。rawText 取原文片段（保留内部注释与换行），
     * sourceSql 才做去注释与空白压缩。
     */
    private void addSqlSegment(Path root, Path file, String content, int from, int to,
                              List<ScanItem> items, int[] counter) {
        // 跳过语句前的空白与整段前置注释，让 rawText 从真正的 SQL 首字符开始
        int start = from;
        while (start < to) {
            char c = content.charAt(start);
            if (Character.isWhitespace(c)) {
                start++;
            } else if (c == '-' && start + 1 < to && content.charAt(start + 1) == '-') {
                int nl = content.indexOf('\n', start);
                start = (nl < 0 || nl >= to) ? to : nl + 1;
            } else if (c == '/' && start + 1 < to && content.charAt(start + 1) == '*') {
                int end = content.indexOf("*/", start);
                start = (end < 0 || end + 2 > to) ? to : end + 2;
            } else {
                break;
            }
        }
        int end = to;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
            end--;
        }
        if (end <= start) {
            return;
        }
        String rawText = content.substring(start, end);
        String sql = stripSqlComments(rawText).replaceAll("\\s+", " ").trim();
        if (sql.isEmpty() || !SQL_START.matcher(sql).find() || !looksLikeSql(sql)) {
            return;
        }
        items.add(newItem(root, file, content, start, rawText, sql, "SQL_FILE", counter));
    }

    private String stripSqlComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }

    // ---------- 通用工具 ----------

    /**
     * 构造 ScanItem 并计算行号
     */
    private ScanItem newItem(Path root, Path file, String content, int offset,
                             String rawText, String sql, String sourceType, int[] counter) {
        ScanItem item = new ScanItem();
        item.setId("s" + (++counter[0]));
        item.setFilePath(file.toAbsolutePath().toString());
        item.setRelativePath(root.relativize(file).toString());
        item.setSourceType(sourceType);
        item.setRawText(rawText);
        item.setRawOffset(offset);
        item.setPlaceholders(findPlaceholders(rawText));
        item.setSourceSql(sql);
        int start = lineOf(content, offset);
        item.setStartLine(start);
        item.setEndLine(start + (int) rawText.chars().filter(c -> c == '\n').count());
        return item;
    }

    /** 提取原始文本中出现的参数占位符（去重、保留出现顺序） */
    private List<String> findPlaceholders(String rawText) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = SQL_PLACEHOLDER.matcher(rawText == null ? "" : rawText);
        while (m.find()) {
            found.add(m.group());
        }
        return new ArrayList<>(found);
    }

    /** 计算偏移量所在行号（1 起） */
    private int lineOf(String content, int offset) {
        if (offset <= 0) {
            return 1;
        }
        int line = 1;
        int max = Math.min(offset, content.length());
        for (int i = 0; i < max; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 粗判是否像一条 SQL（避免误抓短字符串） */
    private boolean looksLikeSql(String sql) {
        if (sql == null || sql.length() < 12) {
            return false;
        }
        String upper = sql.toUpperCase();
        boolean hasKeyword = upper.contains("FROM") || upper.contains("INTO")
                || upper.contains("UPDATE") || upper.contains("SET") || upper.contains("WHERE");
        return SQL_START.matcher(sql).find() && hasKeyword;
    }

    private boolean isInSkippedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将文件中的原始 SQL 文本替换为优化后的 SQL。
     * 替换前生成 .bak 备份。返回是否替换成功。
     *
     * @param item         扫描项（含文件路径与 rawText 定位信息）
     * @param optimizedSql 用户确认的优化后 SQL
     */
    public boolean replace(ScanItem item, String optimizedSql) {
        if (item == null || item.getFilePath() == null) {
            throw new IllegalArgumentException("替换项无效");
        }
        if (optimizedSql == null || optimizedSql.isBlank()) {
            throw new IllegalArgumentException("优化后的 SQL 不能为空");
        }
        Path file = Paths.get(item.getFilePath());
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + item.getFilePath());
        }
        // 只允许改动本进程扫描过的项目目录内的文件（filePath 来自请求体，不可信）
        assertInScannedRoot(file);
        // 动态 MyBatis SQL 不允许整体替换，避免破坏动态标签
        if ("MYBATIS_XML".equals(item.getSourceType())
                && item.getRawText() != null && MYBATIS_DYNAMIC.matcher(item.getRawText()).find()) {
            throw new IllegalStateException("该 SQL 含 MyBatis 动态标签，无法安全整体替换");
        }
        // 参数占位符必须原样保留，否则会把参数绑定改成硬编码常量（业务逻辑被静默改错）
        assertPlaceholdersKept(item, optimizedSql);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String raw = item.getRawText();
            if (raw == null || raw.isEmpty()) {
                throw new IllegalStateException("扫描记录缺少原始 SQL 文本，替换终止");
            }
            int idx = locate(content, raw, item.getRawOffset());

            String replacement = buildReplacement(item, optimizedSql);
            String updated = content.substring(0, idx) + replacement + content.substring(idx + raw.length());

            // 备份用唯一文件名，避免同一文件多次替换时把上一次的备份覆盖成已改动的内容
            Path backup = uniqueBackupPath(file);
            Files.writeString(backup, content, StandardCharsets.UTF_8);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            log.info("已替换 SQL 并备份: {} (备份: {})", file, backup.getFileName());
            return true;
        } catch (IOException e) {
            throw new RuntimeException("替换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 定位 rawText 在文件中的位置。
     * 优先命中扫描时记录的偏移量；若同一文件的前序替换让后续偏移整体位移，
     * 则在所有匹配位置中取「离记录偏移最近」的一处——这样同一文件内的多条重复 SQL
     * 仍能各自替换到正确位置，而不会像取首个匹配那样改错地方。
     */
    private int locate(String content, String raw, int recordedOffset) {
        if (recordedOffset >= 0 && content.startsWith(raw, recordedOffset)) {
            return recordedOffset;
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = content.indexOf(raw); i >= 0; i = content.indexOf(raw, i + 1)) {
            hits.add(i);
        }
        if (hits.isEmpty()) {
            throw new IllegalStateException("未在文件中定位到原始 SQL 文本，可能已被修改，替换终止");
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (recordedOffset < 0) {
            throw new IllegalStateException(
                    "该 SQL 在文件中出现多处且扫描记录缺少位置信息，无法确定替换位置，请重新扫描后再替换");
        }
        int best = hits.get(0);
        for (int hit : hits) {
            if (Math.abs(hit - recordedOffset) < Math.abs(best - recordedOffset)) {
                best = hit;
            }
        }
        return best;
    }

    private void assertPlaceholdersKept(ScanItem item, String optimizedSql) {
        List<String> placeholders = item.getPlaceholders();
        if (placeholders == null || placeholders.isEmpty()) {
            return;
        }
        List<String> missing = placeholders.stream()
                .filter(p -> !optimizedSql.contains(p))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("优化后的 SQL 丢失了参数占位符 " + String.join("、", missing)
                    + "（展示用的 SQL 已把它们替换成 ? / col_x）。直接回写会让参数绑定变成硬编码常量，"
                    + "替换已终止。请先在优化后的 SQL 中把占位符原样写回，再执行替换。");
        }
    }

    private void assertInScannedRoot(Path file) {
        if (scannedRoots.isEmpty()) {
            throw new IllegalStateException("尚未扫描任何项目目录，请先执行扫描再替换");
        }
        String target = canonical(file);
        boolean allowed = scannedRoots.stream()
                .anyMatch(root -> target.equals(root) || target.startsWith(root + File.separator));
        if (!allowed) {
            throw new IllegalArgumentException("目标文件不在本次扫描的项目目录内，拒绝替换: " + file);
        }
    }

    /** 规范化为绝对真实路径，解析 symlink 与 ../，避免路径穿越绕过根目录校验 */
    private String canonical(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    /** 生成不冲突的备份路径：X.sql.20260907-153000.bak，同秒内冲突时追加序号 */
    private Path uniqueBackupPath(Path file) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path backup = file.resolveSibling(file.getFileName() + "." + stamp + ".bak");
        int seq = 1;
        while (Files.exists(backup)) {
            backup = file.resolveSibling(file.getFileName() + "." + stamp + "-" + seq++ + ".bak");
        }
        return backup;
    }

    /**
     * 根据来源类型把优化后的 SQL 包装成与原始文本同形态的替换内容
     */
    private String buildReplacement(ScanItem item, String optimizedSql) {
        String sql = optimizedSql.trim();
        return switch (item.getSourceType()) {
            // 注解实参与普通字符串都替换为单条 Java 字符串字面量
            case "JAVA_STRING", "JAVA_ANNOTATION" ->
                    "\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            // XML 标签体：含 XML 元字符时用 CDATA 包裹，否则会破坏 XML 结构
            case "MYBATIS_XML" -> needsCdata(sql) ? "<![CDATA[" + sql + "]]>" : sql;
            // 独立 .sql 文件直接替换文本内容
            default -> sql;
        };
    }

    private boolean needsCdata(String sql) {
        return sql.contains("<") || sql.contains(">") || sql.contains("&");
    }
}
