package com.sqloptimizer.service;

import com.sqloptimizer.common.OptimizeResult;
import com.sqloptimizer.common.ScanItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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

    @Autowired
    public ProjectScanService(SqlOptimizerService optimizerService) {
        this.optimizerService = optimizerService;
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

        // 逐条优化
        for (ScanItem item : items) {
            try {
                OptimizeResult r = optimizerService.optimize(item.getSourceSql(), enableAi);
                item.setOptimizedSql(r.getOptimizedSql());
                item.setIndexSuggestions(r.getIndexSuggestions());
                item.setTips(r.getTips());
                item.setAiOptimized(r.isAiOptimized());
            } catch (Exception e) {
                item.setOptimizedSql(item.getSourceSql());
                item.getTips().add("该条 SQL 优化失败：" + e.getMessage());
            }
        }
        return items;
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
        // 去行注释后按分号拆分
        String noComment = content.replaceAll("--[^\\n]*", "");
        int searchFrom = 0;
        for (String stmt : noComment.split(";")) {
            String sql = stmt.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("\\s+", " ").trim();
            if (sql.isEmpty() || !SQL_START.matcher(sql).find() || !looksLikeSql(sql)) {
                continue;
            }
            // 用原始（未压缩空白）的片段作为 rawText，便于替换定位
            String rawTrimmed = stmt.trim();
            int idx = content.indexOf(rawTrimmed, searchFrom);
            if (idx >= 0) {
                searchFrom = idx + rawTrimmed.length();
            }
            ScanItem item = newItem(root, file, content, idx >= 0 ? idx : 0, rawTrimmed, sql, "SQL_FILE", counter);
            items.add(item);
        }
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
        item.setSourceSql(sql);
        int start = lineOf(content, offset);
        item.setStartLine(start);
        item.setEndLine(start + (int) rawText.chars().filter(c -> c == '\n').count());
        return item;
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
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("文件不存在: " + item.getFilePath());
        }
        // 动态 MyBatis SQL 不允许整体替换，避免破坏动态标签
        if ("MYBATIS_XML".equals(item.getSourceType())
                && item.getRawText() != null && MYBATIS_DYNAMIC.matcher(item.getRawText()).find()) {
            throw new IllegalStateException("该 SQL 含 MyBatis 动态标签，无法安全整体替换");
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String raw = item.getRawText();
            if (raw == null || !content.contains(raw)) {
                throw new IllegalStateException("未在文件中定位到原始 SQL 文本，可能已被修改，替换终止");
            }
            // 依来源类型构造替换文本
            String replacement = buildReplacement(item, optimizedSql);

            // 只替换第一处匹配，避免误伤重复片段
            int idx = content.indexOf(raw);
            String updated = content.substring(0, idx) + replacement + content.substring(idx + raw.length());

            // 备份
            Path backup = file.resolveSibling(file.getFileName() + ".bak");
            Files.writeString(backup, content, StandardCharsets.UTF_8);
            // 写回
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            log.info("已替换 SQL 并备份: {} (备份: {})", file, backup.getFileName());
            return true;
        } catch (IOException e) {
            throw new RuntimeException("替换失败: " + e.getMessage(), e);
        }
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
            // XML / SQL 文件直接替换文本内容
            default -> sql;
        };
    }
}
