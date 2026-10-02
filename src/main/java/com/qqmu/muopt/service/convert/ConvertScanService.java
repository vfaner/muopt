package com.qqmu.muopt.service.convert;

import com.qqmu.muopt.common.ConversionItem;
import com.qqmu.muopt.common.ConvertScanTask;
import com.qqmu.muopt.service.AiService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConvertScanService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".xml", ".properties", ".yml", ".yaml", ".sql"
    );

    // ========================
    // .sql 文件：匹配完整 SQL 语句（必须以 ; 结尾）
    // ========================
    private static final Pattern SQL_FILE_PATTERN = Pattern.compile(
            "(?i)\\b(SELECT|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE)\\b"
            + "[^;]{5,2000};",
            Pattern.DOTALL
    );

    // ========================
    // .java 文件：匹配字符串字面量（双引号 / Text Block）
    // ========================
    // 双引号字符串（支持转义 \"）
    private static final Pattern JAVA_STRING_PATTERN = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\""
    );
    // Java Text Block（"""..."""）
    private static final Pattern JAVA_TEXT_BLOCK_PATTERN = Pattern.compile(
            "\"\"\"(.*?)\"\"\"",
            Pattern.DOTALL
    );
    // @Query 注解
    private static final Pattern JAVA_QUERY_ANNOTATION_PATTERN = Pattern.compile(
            "@Query\\s*\\(\\s*(?:value\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL
    );

    // ========================
    // .xml 文件：MyBatis mapper 标签
    // ========================
    private static final Pattern MYBATIS_TAG_PATTERN = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ========================
    // .properties / .yml 文件
    // ========================
    private static final Pattern CONFIG_SQL_PATTERN = Pattern.compile(
            "(?i)(?:sql|query|statement|select|insert|update|delete)\\s*[:=]\\s*[\"']?(.+?)(?:[\"']?\\s*$|[\"']?\\s*\\n)",
            Pattern.MULTILINE
    );

    // ========================
    // SQL 有效性校验：判断一个字符串是否"像"真正的 SQL
    // ========================
    private static final Pattern HAS_SELECT_FROM = Pattern.compile(
            "(?i)\\bSELECT\\b.+\\bFROM\\b", Pattern.DOTALL);
    private static final Pattern HAS_INSERT_INTO = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\b", Pattern.DOTALL);
    private static final Pattern HAS_UPDATE_SET = Pattern.compile(
            "(?i)\\bUPDATE\\b.+\\bSET\\b", Pattern.DOTALL);
    private static final Pattern HAS_DELETE_FROM = Pattern.compile(
            "(?i)\\bDELETE\\s+FROM\\b", Pattern.DOTALL);
    private static final Pattern HAS_CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\b", Pattern.DOTALL);
    private static final Pattern HAS_ALTER_TABLE = Pattern.compile(
            "(?i)\\bALTER\\s+TABLE\\b", Pattern.DOTALL);

    // 假阳性过滤：包含这些内容说明不是真正的 SQL
    private static final Pattern FALSE_POSITIVE_INDICATORS = Pattern.compile(
            "(?i)\\bFUNCTION\\s*\\(\\s*\\)"           // FUNCTION() 占位符
            + "|\\bXXX+\\b"                             // XXX 占位符
            + "|\\bTODO\\b"                             // TODO 标记
            + "|\\bFIXME\\b"                            // FIXME 标记
            + "|\\$\\{[^}]+\\}"                         // ${variable} 模板变量（过多时）
            + "|logger\\."                              // 日志调用
            + "|log\\."                                 // 日志调用
            + "|System\\.out"                           // 打印语句
            + "|\\bthrow\\s+new\\b"                     // 异常抛出
            + "|\\bassertEquals\\b"                     // 测试断言
            + "|\\bassertThat\\b"                       // 测试断言
    );

    // 需要跳过的 Java 上下文（字符串前面的代码）
    private static final Set<String> SKIP_CONTEXT_KEYWORDS = Set.of(
            "logger.", "log.", "System.out", "System.err",
            "throw new", "assertEquals", "assertThat", "assertTrue",
            "assertFalse", "fail(", "MessageFormat", "String.format"
    );

    private final SqlConverter sqlConverter;
    private final AiService aiService;
    private final Map<String, ConvertScanTask> scanConvertTasks = new ConcurrentHashMap<>();

    /** AI 润色的条目上限，避免一次扫描打出成千上万次请求 */
    @Value("${app.ai.convert-max-items:100}")
    private int aiMaxItems = 100;

    /** AI 润色的并发线程数（与扫描优化共用同一并发配置） */
    @Value("${app.ai.scan-concurrency:8}")
    private int aiConcurrency = 8;

    /** 批量扫描时单条 AI 请求的超时上限（秒），与模型自身超时取较小值 */
    @Value("${app.ai.scan-per-request-timeout-seconds:45}")
    private int aiPerRequestTimeoutSeconds = 45;

    /** 扫描任务执行线程（守护线程）：任务生命周期与 HTTP 连接无关 */
    private final ExecutorService scanPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "convert-scan");
        t.setDaemon(true);
        return t;
    });

    /** 扫描阶段在总进度里占的百分比（启用 AI 时，剩下的留给 AI 阶段） */
    private static final int SCAN_WEIGHT_WITH_AI = 60;

    /** 已完成任务结果的保留时长（30 分钟） */
    private static final long RETAIN_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /** 内存中同时保留的任务上限 */
    private static final int MAX_TASKS = 20;

    /**
     * 本进程内已扫描过的项目根目录（规范化绝对路径）。
     * 替换只允许作用于这些目录内的文件，防止请求方构造任意路径写入。
     */
    private final Set<String> scannedRoots = ConcurrentHashMap.newKeySet();

    @Autowired
    public ConvertScanService(SqlConverter sqlConverter, AiService aiService) {
        this.sqlConverter = sqlConverter;
        this.aiService = aiService;
    }

    public String startScan(String path, String targetDb) {
        return startScan(path, targetDb, false);
    }

    public String startScan(String path, String targetDb, boolean enableAi) {
        pruneFinished();
        String taskId = UUID.randomUUID().toString().replace("-", "");
        ConvertScanTask task = new ConvertScanTask(taskId, path, targetDb, enableAi);
        scanConvertTasks.put(taskId, task);
        scanPool.submit(() -> scanAsync(task));
        return taskId;
    }

    public void scanAsync(ConvertScanTask task) {
        try {
            Path rootPath = Paths.get(task.getPath());
            if (!Files.exists(rootPath)) {
                throw new IllegalArgumentException("目录不存在: " + task.getPath());
            }
            // 登记为可替换根目录（replace 只允许改动这些目录内的文件）
            scannedRoots.add(canonical(rootPath));

            List<Path> files = new ArrayList<>();
            Files.walkFileTree(rootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 20,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString().toLowerCase();
                            // 跳过常见的非业务目录
                            String absPath = file.toString();
                            if (absPath.contains("/target/") || absPath.contains("/build/")
                                    || absPath.contains("/.git/") || absPath.contains("/node_modules/")
                                    || absPath.contains("/.idea/") || absPath.contains("/.mvn/")) {
                                return FileVisitResult.CONTINUE;
                            }
                            for (String ext : SUPPORTED_EXTENSIONS) {
                                if (name.endsWith(ext)) {
                                    files.add(file);
                                    break;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String dirName = dir.getFileName().toString();
                            if (dirName.startsWith(".") || dirName.equals("target")
                                    || dirName.equals("build") || dirName.equals("node_modules")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });

            int totalFiles = files.size();
            int processedFiles = 0;
            int scanWeight = task.isEnableAi() ? SCAN_WEIGHT_WITH_AI : 100;

            for (Path file : files) {
                try {
                    scanFile(file, task);
                } catch (Exception e) {
                    log.warn("扫描文件失败: {} - {}", file, e.getMessage());
                }
                processedFiles++;
                task.setProgress((processedFiles * scanWeight) / totalFiles);
            }

            if (task.isEnableAi()) {
                optimizeWithAi(task);
            }

            task.complete();
        } catch (Exception e) {
            log.error("扫描任务失败", e);
            task.fail();
        }
    }

    // ============================================================
    //  AI 优化阶段
    // ============================================================

    /**
     * 对规则转换的结果再做一轮 AI 优化。
     * 任何一条 AI 结果没通过安全校验，就保留原有的规则转换结果，不影响整体任务。
     */
    private void optimizeWithAi(ConvertScanTask task) {
        task.setPhase("optimizing");

        List<ConversionItem> items = new ArrayList<>(task.getItems());
        if (items.isEmpty()) {
            task.setProgress(100);
            return;
        }

        if (!aiService.isConfigured()) {
            task.setAiMessage("未配置可用的 AI 模型，已跳过 AI 优化（仅应用规则转换结果）");
            log.info("任务 {} 启用了 AI 优化但 AI 不可用，已跳过", task.getTaskId());
            task.setProgress(100);
            return;
        }

        // 结构脆弱的片段（含 MyBatis 占位符或 XML 标签）不送 AI：
        // 模型会把 #{id} 还原成 ?、把 <if> 标签吃掉，按偏移写回就等于损坏源文件
        List<ConversionItem> eligible = new ArrayList<>(items.size());
        int fragileCount = 0;
        for (ConversionItem item : items) {
            if (isAiEligible(item)) {
                eligible.add(item);
            } else {
                fragileCount++;
            }
        }

        if (eligible.isEmpty()) {
            task.setAiSkipped(items.size());
            task.setAiMessage("待改造片段均含动态 SQL 占位符或 XML 标签，为避免破坏源文件已跳过 AI 优化");
            task.setProgress(100);
            return;
        }

        int limit = Math.min(eligible.size(), Math.max(1, aiMaxItems));
        task.setAiTotal(limit);
        task.setAiSkipped(items.size() - limit);

        List<String> skipReasons = new ArrayList<>(2);
        if (eligible.size() > limit) {
            skipReasons.add("条目数超过上限 " + limit + " 条，其余 " + (eligible.size() - limit)
                    + " 条仅使用规则转换结果");
        }
        if (fragileCount > 0) {
            skipReasons.add(fragileCount + " 条含动态 SQL 占位符或 XML 标签，为避免破坏源文件未送 AI");
        }
        if (!skipReasons.isEmpty()) {
            task.setAiMessage(String.join("；", skipReasons));
        }

        int threads = Math.min(Math.max(1, aiConcurrency), limit);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ai-optimize-" + task.getTaskId());
            t.setDaemon(true);
            return t;
        });

        try {
            List<Future<?>> futures = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                ConversionItem item = eligible.get(i);
                futures.add(pool.submit(() -> optimizeItem(item, task)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.debug("AI 优化子任务异常: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }

        log.info("任务 {} AI 优化完成：应用 {} 条，失败/拒绝 {} 条，跳过 {} 条",
                task.getTaskId(), task.getAiApplied(), task.getAiFailed(), task.getAiSkipped());
        task.setProgress(100);
    }

    /**
     * 判断该条目是否可以交给 AI 重写。
     * <p>AI 只保证输出「一条合法 SQL」，不保证保留 MyBatis 占位符和 XML 动态标签。
     * 而按偏移写回是原样覆盖，占位符一旦丢失就是源文件损坏，因此这类片段一律不送 AI。
     */
    private boolean isAiEligible(ConversionItem item) {
        String source = item.getSourceSql();
        if (source == null) return false;
        if (source.contains("#{") || source.contains("${")) return false;
        return !XML_TAG_IN_SQL.matcher(source).find();
    }

    /** 与 SqlConverter 的掩码保持同样的保守写法：{@code <} 后必须紧跟字母或 /，避免误判 {@code a < 5}。 */
    private static final Pattern XML_TAG_IN_SQL =
            Pattern.compile("</?[A-Za-z][\\w:.-]*(?:\\s[^<>]*)?/?>");

    private void optimizeItem(ConversionItem item, ConvertScanTask task) {
        String ruleSql = item.getTargetSql();
        try {
            String aiRaw = aiService.polishConvertedSql(ruleSql, task.getTargetDb(), aiPerRequestTimeoutSeconds);
            String safeSql = sanitizeAiSql(aiRaw, item);

            if (safeSql == null) {
                task.incrementAiFailed();
            } else if (!safeSql.equals(ruleSql)) {
                item.setRuleSql(ruleSql);
                item.setTargetSql(safeSql);
                item.setAiOptimized(true);
                item.setConversionType(item.getConversionType() + " + AI优化");
                task.incrementAiApplied();
            }
            // safeSql 与规则结果一致：AI 认为无需再改，什么都不做
        } catch (Exception e) {
            task.incrementAiFailed();
            log.debug("AI 优化条目失败（保留规则转换结果）: {} - {}",
                    item.getFilePath(), e.getMessage());
        } finally {
            int done = task.incrementAiDone();
            int total = Math.max(1, task.getAiTotal());
            task.setProgress(SCAN_WEIGHT_WITH_AI
                    + (done * (100 - SCAN_WEIGHT_WITH_AI)) / total);
        }
    }

    /**
     * AI 输出的安全校验与归一化。
     * <p>
     * 这一步是必须的：{@code FileReplacerService} 会把 targetSql 原样写回 .java/.xml 源文件，
     * 一旦模型返回 Markdown 围栏、多行文本或裸双引号，就会直接破坏源码语法。
     * 校验不通过时返回 {@code null}，调用方保留规则转换结果。
     *
     * @return 可安全写回文件的 SQL；不可信时返回 null
     */
    String sanitizeAiSql(String aiRaw, ConversionItem item) {
        if (aiRaw == null) return null;

        String s = AiService.stripCodeFence(aiRaw).trim();
        if (s.isEmpty()) return null;

        // 仍然残留反引号 => 模型输出结构不干净，不可信
        if (s.indexOf('`') >= 0) return null;

        String ruleSql = item.getTargetSql() == null ? "" : item.getTargetSql();
        boolean isSqlFile = item.getFilePath() != null
                && item.getFilePath().toLowerCase().endsWith(".sql");

        if (!isSqlFile) {
            // 源码/配置文件里的 SQL 是单行字符串字面量，
            // 多行内容替换进去会破坏 Java 字面量或 XML 属性，必须压成单行
            s = s.replaceAll("\\s+", " ").trim();
            // 裸双引号会提前结束 Java 字符串字面量；反斜杠会引入意外转义
            if (s.indexOf('"') >= 0 || s.indexOf('\\') >= 0) return null;
        }

        // 结尾分号必须和「文件里被替换掉的原文」一致，否则会吞掉或多出语句分隔符。
        // 以 sourceSql 为准（它才是真正会被替换的那段文本），sourceSql 缺失时退回规则结果。
        String terminatorRef = (item.getSourceSql() != null && !item.getSourceSql().isBlank())
                ? item.getSourceSql() : ruleSql;
        boolean refEndsWithSemicolon = terminatorRef.trim().endsWith(";");
        boolean aiEndsWithSemicolon = s.endsWith(";");
        if (refEndsWithSemicolon && !aiEndsWithSemicolon) {
            s = s + ";";
        } else if (!refEndsWithSemicolon && aiEndsWithSemicolon) {
            s = s.substring(0, s.length() - 1).trim();
        }

        // 输出必须仍然是一条 SQL（复用扫描阶段的校验器）
        List<String> validated = new ArrayList<>(1);
        addIfValidSql(s, validated);
        if (validated.isEmpty()) return null;

                // 非 ASCII 字符：只允许原文里已经出现过的（例如 WHERE name = '张三' 这类中文字面量），
        // 出现原文没有的中文说明模型夹带了解释性文字
        Set<Character> allowedNonAscii = new HashSet<>();
        for (int i = 0; i < ruleSql.length(); i++) {
            char c = ruleSql.charAt(i);
            if (c > 127) allowedNonAscii.add(c);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 && !allowedNonAscii.contains(c)) return null;
        }

        // 长度暴增通常意味着模型夹带了解释性文字
        if (s.length() > ruleSql.length() * 3 + 200) return null;

        return s;
    }

    /**
     * 扫描到的一段 SQL：原文片段 + 它在文件中的确切位置。
     * <p>{@code rawText} 是文件里逐字存在的内容（含换行、缩进、{@code #{}}、Java 转义），
     * 替换时按 {@code [start, end)} 原样覆盖，因此不再依赖"归一化文本能否匹配上原文"。
     * {@code normalized} 只用于有效性判断与日志，不参与替换。
     */
    private static class SqlFragment {
        final String rawText;
        final int start;
        final int end;
        final String normalized;

        SqlFragment(String rawText, int start, int end, String normalized) {
            this.rawText = rawText;
            this.start = start;
            this.end = end;
            this.normalized = normalized;
        }
    }

    private void scanFile(Path file, ConvertScanTask task) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String fileName = file.getFileName().toString().toLowerCase();

        List<SqlFragment> fragments = new ArrayList<>();

        if (fileName.endsWith(".java")) {
            fragments.addAll(extractSqlFromJava(content));
        } else if (fileName.endsWith(".xml")) {
            fragments.addAll(extractSqlFromXml(content));
        } else if (fileName.endsWith(".properties") || fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            fragments.addAll(extractSqlFromConfig(content));
        } else if (fileName.endsWith(".sql")) {
            fragments.addAll(extractSqlFromSqlFile(content));
        }

        for (SqlFragment fragment : dropOverlapping(fragments)) {
            try {
                // 保形转换：结果要按位置写回源文件，不能经过 JSqlParser 重排
                String convertedSql = sqlConverter.convertPreservingText(fragment.rawText, task.getTargetDb());
                if (fragment.rawText.equals(convertedSql)) continue;

                ConversionItem item = new ConversionItem(
                        file.toString(),
                        fragment.rawText,
                        convertedSql,
                        determineConversionType(fragment.rawText, convertedSql),
                        lineNumberAt(content, fragment.start)
                );
                item.setStartOffset(fragment.start);
                item.setEndOffset(fragment.end);
                task.addItem(item);
            } catch (Exception e) {
                log.debug("转换SQL失败（已跳过）: {} - {}",
                        fragment.normalized.substring(0, Math.min(60, fragment.normalized.length())),
                        e.getMessage());
            }
        }
    }

    /**
     * 丢弃区间重叠的片段，只保留先出现、更长的那个。
     * <p>Java 提取会用三个正则（@Query / Text Block / 普通字符串）扫同一份内容，
     * 同一段文本可能被命中多次；按位置替换要求区间互不重叠，否则下标会互相错位。
     */
    private List<SqlFragment> dropOverlapping(List<SqlFragment> fragments) {
        List<SqlFragment> sorted = new ArrayList<>(fragments);
        sorted.sort((a, b) -> a.start != b.start
                ? Integer.compare(a.start, b.start)
                : Integer.compare(b.end - b.start, a.end - a.start));

        List<SqlFragment> accepted = new ArrayList<>();
        int lastEnd = -1;
        for (SqlFragment f : sorted) {
            if (f.start < lastEnd) continue;    // 与已接受区间重叠
            accepted.add(f);
            lastEnd = f.end;
        }
        return accepted;
    }

    /** 计算偏移量所在的行号（1 起）。此前这里恒为 0，前端无法定位。 */
    private int lineNumberAt(String content, int offset) {
        int line = 1;
        int limit = Math.min(offset, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ============================================================
    //  Java 文件 SQL 提取
    // ============================================================
    private List<SqlFragment> extractSqlFromJava(String content) {
        List<SqlFragment> fragments = new ArrayList<>();

        // 1. @Query 注解中的 SQL（优先级最高、最可靠）
        Matcher queryMatcher = JAVA_QUERY_ANNOTATION_PATTERN.matcher(content);
        while (queryMatcher.find()) {
            addFragment(fragments, content, queryMatcher.start(1), queryMatcher.end(1),
                    cleanJavaString(queryMatcher.group(1)));
        }

        // 2. Text Block（"""..."""）
        Matcher textBlockMatcher = JAVA_TEXT_BLOCK_PATTERN.matcher(content);
        while (textBlockMatcher.find()) {
            String normalized = textBlockMatcher.group(1)
                    .replace("\"", "").replace("+", " ")
                    .replaceAll("\\s+", " ").trim();
            addFragment(fragments, content, textBlockMatcher.start(1), textBlockMatcher.end(1),
                    normalized);
        }

        // 3. 普通双引号字符串
        Matcher stringMatcher = JAVA_STRING_PATTERN.matcher(content);
        while (stringMatcher.find()) {
            int start = stringMatcher.start();
            // 检查字符串前面的上下文，排除日志/断言等
            String contextBefore = content.substring(Math.max(0, start - 100), start);
            if (isSkipContext(contextBefore)) continue;

            addFragment(fragments, content, stringMatcher.start(1), stringMatcher.end(1),
                    cleanJavaString(stringMatcher.group(1)));
        }

        return fragments;
    }

    /**
     * 用归一化后的文本做有效性判断，但登记的是原文区间。
     * <p>两者分离是本次改造的核心：判断"像不像 SQL"需要归一化，
     * 而写回源文件必须用原文。
     */
    private void addFragment(List<SqlFragment> fragments, String content,
                             int start, int end, String normalized) {
        if (start < 0 || end > content.length() || end <= start) return;
        if (!isValidSql(normalized)) return;
        fragments.add(new SqlFragment(content.substring(start, end), start, end, normalized));
    }

    /**
     * 检查字符串前面的上下文是否表明这不是 SQL
     */
    private boolean isSkipContext(String contextBefore) {
        for (String keyword : SKIP_CONTEXT_KEYWORDS) {
            if (contextBefore.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 清理 Java 字符串字面量中的转义和拼接
     */
    private String cleanJavaString(String raw) {
        if (raw == null) return "";
        // 还原常见转义
        String s = raw.replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\\\", "\\");
        // 去掉字符串拼接的 "+ 和 +"
        s = s.replaceAll("\"\\s*\\+\\s*\"", " ");
        // 合并空白
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    // ============================================================
    //  XML 文件 SQL 提取（MyBatis mapper）
    // ============================================================
    private List<SqlFragment> extractSqlFromXml(String content) {
        List<SqlFragment> fragments = new ArrayList<>();

        Matcher matcher = MYBATIS_TAG_PATTERN.matcher(content);
        while (matcher.find()) {
            // 归一化只为判断"是不是 SQL"；登记的仍是含标签与 #{} 的原文区间
            addFragment(fragments, content, matcher.start(2), matcher.end(2),
                    normalizeMyBatisSql(matcher.group(2)));
        }

        return fragments;
    }

    /**
     * 把 mapper 片段压成纯 SQL，用于有效性判断。
     * <p>注意结果**不能**用来写回文件——{@code #{id}} 变成了 {@code ?}、标签和换行都没了，
     * 原样写回会毁掉 mapper。这正是早先"扫得出来改不进去"的根因。
     */
    private String normalizeMyBatisSql(String rawSql) {
        String sql = rawSql.trim();
        if (sql.length() < 10) return "";

        // 移除 CDATA 包裹
        sql = sql.replaceAll("<!\\[CDATA\\[", "").replaceAll("]]>", "");

        // 移除 MyBatis 动态标签，但保留里面的内容
        sql = sql.replaceAll("<if\\b[^>]*>", " ")
                .replaceAll("</if>", " ")
                .replaceAll("<where\\b[^>]*>", " WHERE ")
                .replaceAll("</where>", " ")
                .replaceAll("<set\\b[^>]*>", " SET ")
                .replaceAll("</set>", " ")
                .replaceAll("<choose\\b[^>]*>", " ")
                .replaceAll("</choose>", " ")
                .replaceAll("<when\\b[^>]*>", " ")
                .replaceAll("</when>", " ")
                .replaceAll("<otherwise\\b[^>]*>", " ")
                .replaceAll("</otherwise>", " ")
                .replaceAll("<foreach\\b[^>]*>", " ")
                .replaceAll("</foreach>", " ")
                .replaceAll("<trim\\b[^>]*>", " ")
                .replaceAll("</trim>", " ")
                .replaceAll("<include\\b[^>]*/?>", " ");

        // 移除其他 XML 标签
        sql = sql.replaceAll("<[^>]+>", " ");

        // MyBatis 参数 #{xxx} 和 ${xxx} 替换为占位符 ?
        sql = sql.replaceAll("#\\{[^}]*}", "?");
        sql = sql.replaceAll("\\$\\{[^}]*}", "?");

        // 合并空白
        return sql.replaceAll("\\s+", " ").trim();
    }

    // ============================================================
    //  配置文件 SQL 提取
    // ============================================================
    private List<SqlFragment> extractSqlFromConfig(String content) {
        List<SqlFragment> fragments = new ArrayList<>();
        Matcher matcher = CONFIG_SQL_PATTERN.matcher(content);
        while (matcher.find()) {
            // 收缩区间以剔除两端空白与收尾的引号，保证 rawText 与区间严格对应
            int[] span = shrinkSpan(content, matcher.start(1), matcher.end(1));
            if (span == null) continue;
            addFragment(fragments, content, span[0], span[1],
                    content.substring(span[0], span[1]));
        }
        return fragments;
    }

    /** 从两端收缩掉空白与收尾引号，返回调整后的 [start, end)；无有效内容时返回 null。 */
    private int[] shrinkSpan(String content, int start, int end) {
        while (start < end && Character.isWhitespace(content.charAt(start))) start++;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
        while (end > start && (content.charAt(end - 1) == '"' || content.charAt(end - 1) == '\'')) {
            end--;
            while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
        }
        return end > start ? new int[]{start, end} : null;
    }

    // ============================================================
    //  .sql 文件 SQL 提取
    // ============================================================
    private List<SqlFragment> extractSqlFromSqlFile(String content) {
        List<SqlFragment> fragments = new ArrayList<>();

        // 注释用等长空格替换而不是删除：长度不变，matcher 的下标才能直接映射回原文
        String blanked = blankOutSqlComments(content);

        Matcher matcher = SQL_FILE_PATTERN.matcher(blanked);
        while (matcher.find()) {
            int[] span = shrinkSpan(content, matcher.start(), matcher.end());
            if (span == null) continue;
            addFragment(fragments, content, span[0], span[1],
                    content.substring(span[0], span[1]));
        }
        return fragments;
    }

    /** 把 SQL 注释替换成等长空格，保持整体长度与后续下标不变。 */
    private String blankOutSqlComments(String content) {
        StringBuilder sb = new StringBuilder(content);
        blankMatches(Pattern.compile("--[^\n]*"), sb);
        blankMatches(Pattern.compile("/\\*[\\s\\S]*?\\*/"), sb);
        return sb.toString();
    }

    private void blankMatches(Pattern pattern, StringBuilder sb) {
        Matcher m = pattern.matcher(sb);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (sb.charAt(i) != '\n') sb.setCharAt(i, ' ');
            }
        }
    }

    // ============================================================
    //  SQL 有效性校验（核心过滤器）
    // ============================================================

    /**
     * 判断一个字符串是否是有效的、值得转换的 SQL 语句。
     */
    private boolean isValidSql(String sql) {
        if (sql == null) return false;
        sql = sql.trim();

        // 1. 长度过滤
        if (sql.length() < 15) return false;

        // 2. 假阳性过滤
        if (FALSE_POSITIVE_INDICATORS.matcher(sql).find()) return false;

        // 3. 必须包含 SQL 关键字
        String upper = sql.toUpperCase();
        boolean hasKeyword = upper.contains("SELECT") || upper.contains("INSERT")
                || upper.contains("UPDATE") || upper.contains("DELETE")
                || upper.contains("CREATE TABLE") || upper.contains("ALTER TABLE")
                || upper.contains("DROP TABLE");
        if (!hasKeyword) return false;

        // 4. 结构校验：SELECT 必须有 FROM，INSERT 必须有 INTO，UPDATE 必须有 SET，DELETE 必须有 FROM
        boolean validStructure = false;
        if (upper.contains("SELECT")) {
            // SELECT 语句：必须有 FROM（子查询除外，简单检查）
            // 特例：SELECT 1, SELECT COUNT(*) 等可以没有 FROM
            if (HAS_SELECT_FROM.matcher(sql).find()
                    || upper.matches(".*SELECT\\s+\\d+.*")
                    || upper.matches(".*SELECT\\s+\\w+\\s*\\(.*")) {
                validStructure = true;
            }
        }
        if (upper.contains("INSERT")) {
            if (HAS_INSERT_INTO.matcher(sql).find()) validStructure = true;
        }
        if (upper.contains("UPDATE") && !upper.contains("INSERT")) {
            if (HAS_UPDATE_SET.matcher(sql).find()) validStructure = true;
        }
        if (upper.startsWith("DELETE") || upper.contains("DELETE FROM")) {
            if (HAS_DELETE_FROM.matcher(sql).find()) validStructure = true;
        }
        if (HAS_CREATE_TABLE.matcher(sql).find()) validStructure = true;
        if (HAS_ALTER_TABLE.matcher(sql).find()) validStructure = true;

        if (!validStructure) return false;

        // 5. 过滤纯控制流片段（IF/WHILE/BEGIN/END 开头、没有独立 DML 的）
        String trimmed = sql.trim();
        if (trimmed.toUpperCase().startsWith("IF") && !trimmed.toUpperCase().startsWith("IF EXISTS")) return false;
        if (trimmed.toUpperCase().startsWith("WHILE")) return false;
        if (trimmed.toUpperCase().startsWith("BEGIN")) return false;
        if (trimmed.toUpperCase().startsWith("END")) return false;

        // 6. 过滤含有过多 Java/代码痕迹的字符串
        long parenCount = sql.chars().filter(c -> c == '(').count();
        long closeParenCount = sql.chars().filter(c -> c == ')').count();
        if (Math.abs(parenCount - closeParenCount) > 2) return false; // 括号严重不匹配

        // 7. 过滤含有明显 Java 代码片段的
        if (sql.contains("public ") || sql.contains("private ") || sql.contains("void ")
                || sql.contains("return ") || sql.contains("new ") || sql.contains(".get(")
                || sql.contains(".set(") || sql.contains("this.")) return false;

        return true;
    }

    /** 校验通过则收集（AI 结果校验仍在用这个形式）。 */
    private void addIfValidSql(String sql, List<String> result) {
        if (isValidSql(sql)) {
            result.add(sql.trim());
        }
    }

    // ============================================================
    //  转换类型判定
    // ============================================================
    private String determineConversionType(String source, String target) {
        List<String> types = new ArrayList<>();
        String srcUpper = source.toUpperCase();
        String tgtUpper = target.toUpperCase();

        if (srcUpper.contains("LIMIT") && tgtUpper.contains("FETCH FIRST")) {
            types.add("语法转换(LIMIT→FETCH)");
        }
        if (srcUpper.contains("LIMIT") && tgtUpper.contains("ROWNUM")) {
            types.add("语法转换(LIMIT→ROWNUM)");
        }
        if (containsFunction(source, "IFNULL") && !containsFunction(target, "IFNULL")) {
            types.add("函数转换(IFNULL)");
        }
        if (containsFunction(source, "NOW") && !containsFunction(target, "NOW")) {
            types.add("函数转换(NOW)");
        }
        if (containsFunction(source, "DATE_FORMAT") && !containsFunction(target, "DATE_FORMAT")) {
            types.add("函数转换(DATE_FORMAT)");
        }
        if (containsFunction(source, "CONCAT") && !containsFunction(target, "CONCAT")) {
            types.add("函数转换(CONCAT)");
        }
        if (containsFunction(source, "SUBSTRING") && !containsFunction(target, "SUBSTRING")) {
            types.add("函数转换(SUBSTRING)");
        }
        // 回迁 MySQL 方向：源是国产库/Oracle 写法，目标是 MySQL
        if (srcUpper.contains("ROWNUM") && tgtUpper.contains("LIMIT")) {
            types.add("语法转换(ROWNUM→LIMIT)");
        }
        if (srcUpper.contains("FETCH FIRST") && tgtUpper.contains("LIMIT")) {
            types.add("语法转换(FETCH→LIMIT)");
        }
        if (containsFunction(source, "NVL") && !containsFunction(target, "NVL")) {
            types.add("函数转换(NVL)");
        }
        if (containsFunction(source, "TO_CHAR") && !containsFunction(target, "TO_CHAR")) {
            types.add("函数转换(TO_CHAR)");
        }
        if (srcUpper.contains("SYSDATE") && !tgtUpper.contains("SYSDATE")) {
            types.add("函数转换(SYSDATE)");
        }
        if (types.isEmpty()) {
            types.add("SQL转换");
        }
        return String.join(", ", types);
    }

    private boolean containsFunction(String sql, String funcName) {
        return sql.toUpperCase().contains(funcName.toUpperCase() + "(");
    }

    public ConvertScanTask getConvertScanTask(String taskId) {
        return scanConvertTasks.get(taskId);
    }

    public Map<String, ConvertScanTask> getAllTasks() {
        return scanConvertTasks;
    }

    /**
     * 目标文件是否位于某个已扫描登记的项目根目录内。
     * 替换写回前的强制校验：自定义清单可能由客户端任意构造，必须防路径穿越。
     */
    public boolean isUnderScannedRoot(String filePath) {
        if (filePath == null || scannedRoots.isEmpty()) {
            return false;
        }
        String target = canonical(Paths.get(filePath));
        return scannedRoots.stream()
                .anyMatch(root -> target.equals(root)
                        || target.startsWith(root + java.io.File.separator));
    }

    /** 规范化为绝对真实路径，解析 symlink 与 ../，避免路径穿越绕过根目录校验 */
    private String canonical(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    /** 清理过期任务，并在超上限时淘汰最早结束的任务 */
    private void pruneFinished() {
        long now = System.currentTimeMillis();
        scanConvertTasks.entrySet().removeIf(e -> {
            ConvertScanTask t = e.getValue();
            return !"running".equals(t.getStatus())
                    && t.getEndTime() > 0
                    && now - t.getEndTime() > RETAIN_MILLIS;
        });
        if (scanConvertTasks.size() < MAX_TASKS) {
            return;
        }
        scanConvertTasks.values().stream()
                .filter(t -> !"running".equals(t.getStatus()))
                .sorted(Comparator.comparingLong(ConvertScanTask::getEndTime))
                .limit(scanConvertTasks.size() - MAX_TASKS + 1L)
                .forEach(t -> scanConvertTasks.remove(t.getTaskId()));
    }
}
