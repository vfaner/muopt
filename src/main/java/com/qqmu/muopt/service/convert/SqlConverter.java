package com.qqmu.muopt.service.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
public class SqlConverter {

    private final Map<String, DialectConfig> dialectConfigs = new HashMap<>();

    @PostConstruct
    public void init() {
        // 方言配置按「数据库家族」生成，同族共享同一份配置（语法兼容，映射一致）。
        // PostgreSQL 家族：GaussDB / openGauss / 瀚高 / 海量 Vastbase 均是 PG 内核，
        // FETCH FIRST / COALESCE / TO_CHAR / CURRENT_TIMESTAMP 与 PG 一致；
        // DB2 同样支持 FETCH FIRST n ROWS ONLY 与 CURRENT_TIMESTAMP，复用无害。
        DialectConfig pgFamily = createGaussDbConfig();
        dialectConfigs.put("gaussdb", pgFamily);
        dialectConfigs.put("postgresql", pgFamily);
        dialectConfigs.put("opengauss", pgFamily);
        dialectConfigs.put("highgo", pgFamily);
        dialectConfigs.put("vastbase", pgFamily);
        dialectConfigs.put("db2", pgFamily);

        // Oracle 家族：达梦是仿 Oracle 方言，崖山 YashanDB 也以 Oracle 兼容为主，
        // ROWNUM / SYSDATE / NVL / TO_CHAR / IDENTITY 三库一致，共享配置。
        DialectConfig oracleFamily = createDamengConfig();
        dialectConfigs.put("dameng", oracleFamily);
        dialectConfigs.put("oracle", oracleFamily);
        dialectConfigs.put("yashandb", oracleFamily);

        dialectConfigs.put("kingbase", createKingbaseConfig());

        // MySQL 兼容家族：均保留 MySQL 写法（函数/类型/LIMIT 恒等）。
        dialectConfigs.put("oceanbase", createOceanBaseConfig());
        dialectConfigs.put("tidb", createTiDbConfig());
        dialectConfigs.put("golden", createGoldenDbConfig());
        // MariaDB 与 GoldenDB 同为 MySQL 兼容恒等，直接复用
        dialectConfigs.put("mariadb", createGoldenDbConfig());

        dialectConfigs.put("gbase", createGBaseConfig());
        dialectConfigs.put("shentong", createShenTongConfig());
        dialectConfigs.put("sqlserver", createSqlServerConfig());
        dialectConfigs.put("mysql", createMysqlConfig());
    }

    /**
     * 手工处理入口。
     * <p>
     * 这里曾经先用 JSqlParser 解析、再对 {@code statement.toString()} 套正则，解析失败才降级。
     * 但 AST 从来没有参与实际改写——{@code convertStatement} 只是"toString 之后套同一批正则"，
     * 所以那一层解析不提供任何转换能力，只带来三样纯损失：
     * <ul>
     *   <li>{@code toString()} 会重排 SQL，抹掉用户自己的缩进和换行，还会产出
     *       {@code VARCHAR (50)}、{@code DECIMAL (10, 2)} 这种多一个空格的写法；</li>
     *   <li>凡是含 MyBatis {@code #{}} 的语句必然解析失败，每次都往日志里丢一整段
     *       多行 ParseException——那本来是设计好的正常降级路径，不是故障；</li>
     *   <li>降级路径与 AST 路径的替换步骤长期不一致（降级少调了一次 {@code convertSyntax}，
     *       于是解析失败的语句静默丢掉 AUTO_INCREMENT 转换），属于"改动只落在一条路径上"的老问题。</li>
     * </ul>
     * 现在与保形转换共用同一条实现，全项目只剩一条转换路径。
     */
    public String convert(String sourceSql, String targetDb) {
        return preserveStatementTerminator(sourceSql, convertPreservingText(sourceSql, targetDb));
    }

    /**
     * 保形转换：只做局部的函数/类型/语法替换，**不经过 JSqlParser 重排**。
     * <p>
     * 自动改造要把结果原样写回源文件，因此必须保住原文的一切细节——换行、缩进、
     * MyBatis 的 {@code #{id}}、XML 标签、Java 字符串里的 {@code \n} 转义。
     * 走 {@link #convert} 的话 {@code statement.toString()} 会把这些全部抹平，
     * 写回去等于毁掉 mapper 和 Java 源码。
     * <p>
     * 手工处理的 {@link #convert} 现在也共用这条实现，全项目只有一条转换路径。
     */
    public String convertPreservingText(String rawSql, String targetDb) {
        DialectConfig config = requireConfig(rawSql, targetDb);

        String result = rawSql;
        result = convertFunctions(result, config);
        result = convertTypes(result, config);
        result = convertSyntax(result, config);
        return result;
    }

    private DialectConfig requireConfig(String sourceSql, String targetDb) {
        if (sourceSql == null || sourceSql.trim().isEmpty()) {
            throw new IllegalArgumentException("源SQL不能为空");
        }
        if (targetDb == null || targetDb.trim().isEmpty()) {
            throw new IllegalArgumentException("目标数据库不能为空");
        }

        DialectConfig config = dialectConfigs.get(targetDb.toLowerCase().trim());
        if (config == null) {
            throw new IllegalArgumentException("不支持的目标数据库: " + targetDb);
        }
        return config;
    }

    /**
     * 还原语句结尾的分号。
     * <p>
     * 现在的转换路径是纯文本替换，本来就不会丢分号，所以这一步实际是个幂等的安全网：
     * 只在"原文有分号、结果没有"时补上。留着它是因为 .sql 脚本里一旦丢掉分号，
     * 相邻两条语句就会粘连成一条，代价远大于多留这几行判断。
     */
    private String preserveStatementTerminator(String sourceSql, String converted) {
        if (converted == null) return null;
        if (!sourceSql.trim().endsWith(";")) return converted;

        String trimmedEnd = converted.stripTrailing();
        if (trimmedEnd.endsWith(";")) return converted;
        return trimmedEnd + ";";
    }

    private String convertFunctions(String sql, DialectConfig config) {
        String result = sql;

        // DATE_FORMAT 的格式串是 MySQL 私有写法（'%Y-%m-%d'）。目标方言若把它换成 TO_CHAR，
        // 光改函数名是不够的：TO_CHAR(d, '%Y-%m-%d') 在达梦/Oracle 系会因格式串非法而报错，
        // 在 PG 系（金仓/GaussDB）则会把 %Y 原样打印出来。转换"看起来成功、执行必错"是最坏的情况，
        // 所以必须趁函数还叫 DATE_FORMAT（能确定格式串是 MySQL 方言）时先把格式串翻掉。
        String dateFormatTarget = config.functions == null ? null : config.functions.get("DATE_FORMAT");
        if (dateFormatTarget != null && !"DATE_FORMAT".equalsIgnoreCase(dateFormatTarget)) {
            result = convertDateFormatPatterns(result);
        }

        // 反方向同理：目标是 MySQL 系时 TO_CHAR -> DATE_FORMAT，格式串也必须一起翻回 '%Y-%m-%d'，
        // 否则产出 DATE_FORMAT(d, 'YYYY-MM-DD')，与上面那个坑是同一个错、只是方向相反。
        String toCharTarget = config.functions == null ? null : config.functions.get("TO_CHAR");
        if ("DATE_FORMAT".equalsIgnoreCase(toCharTarget)) {
            result = convertToCharPatterns(result);
        }

        // 裸关键字改函数调用：Oracle 系的 SYSDATE 不带括号，MySQL 的 NOW() 必须带，
        // 通用映射那套 "函数名 + (" 的正则匹配不到它。
        for (Map.Entry<String, String> entry : config.bareKeywords.entrySet()) {
            Pattern barePattern = Pattern.compile(
                "\\b" + Pattern.quote(entry.getKey()) + "\\b(?!\\s*\\()",
                Pattern.CASE_INSENSITIVE
            );
            result = replaceOutsideLiterals(result, barePattern, entry.getValue());
        }

        for (Map.Entry<String, String> entry : config.functions.entrySet()) {
            String sourceFunc = entry.getKey().toUpperCase();
            String targetFunc = entry.getValue();

            // 恒等映射（如 MySQL 兼容库的 NOW -> NOW）没什么可改的，而且必须提前跳过：
            // 下面"零参数调用"那支看到目标名不含括号就会去掉括号，把 NOW() 削成 NOW。
            // 保留恒等项本身是有意义的——正向的 DATE_FORMAT 格式串翻译就靠它表达"此方言保留原样"。
            if (sourceFunc.equalsIgnoreCase(targetFunc)) {
                continue;
            }

            // 处理带参数的函数
            if (targetFunc.contains("{0}")) {
                // 带参数的函数，如 DATE_FORMAT -> TO_CHAR({0}, {1})
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(([^)]+)\\)",
                    Pattern.CASE_INSENSITIVE
                );
                result = replaceOutsideLiterals(result, pattern, m -> {
                    String[] argParts = m.group(1).split(",", -1);
                    String replacement = targetFunc;
                    for (int i = 0; i < argParts.length; i++) {
                        replacement = replacement.replace("{" + i + "}", argParts[i].trim());
                    }
                    return replacement;
                });
            } else {
                // 零参数调用（如 NOW()）：目标若是不带括号的关键字，必须把括号一起消掉。
                // 否则会产出 CURRENT_TIMESTAMP() / SYSDATE() —— 这在 PG 系和 Oracle 系都是非法语法。
                if (!targetFunc.contains("(")) {
                    Pattern noArgPattern = Pattern.compile(
                        "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(\\s*\\)",
                        Pattern.CASE_INSENSITIVE
                    );
                    result = replaceOutsideLiterals(result, noArgPattern, targetFunc);
                }

                // 带参数调用：只换函数名，保留括号和参数
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(",
                    Pattern.CASE_INSENSITIVE
                );
                result = replaceOutsideLiterals(result, pattern, targetFunc + "(");
            }
        }

        return result;
    }

    /** 一次 DATE_FORMAT 调用；参数里不允许再嵌套括号，嵌套的场合交给下面的字面量判定兜底。 */
    private static final Pattern DATE_FORMAT_CALL = Pattern.compile(
            "\\bDATE_FORMAT\\s*\\(([^()]*)\\)",
            Pattern.CASE_INSENSITIVE
    );

    /** 参数列表末尾的字符串字面量，即 DATE_FORMAT 的格式串。{@code ''} 是 SQL 里的转义单引号。 */
    private static final Pattern TRAILING_FORMAT_LITERAL = Pattern.compile(
            "'((?:[^']|'')*)'\\s*$"
    );

    /**
     * MySQL DATE_FORMAT 格式符 -> TO_CHAR 格式符。
     * <p>这里每一项在 Oracle 系（达梦）和 PostgreSQL 系（金仓、GaussDB）的 TO_CHAR 里写法一致，
     * 因此一张表足够覆盖三个方言。刻意不收录 {@code %f}（微秒）之类两族写法分歧的格式符
     * （Oracle 是 {@code FF6}、PG 是 {@code US}），猜错比不翻更难排查。
     */
    private static final Map<Character, String> MYSQL_DATE_SPECIFIERS = Map.ofEntries(
            Map.entry('Y', "YYYY"),   // 四位年
            Map.entry('y', "YY"),     // 两位年
            Map.entry('m', "MM"),     // 月，补零
            Map.entry('d', "DD"),     // 日，补零
            Map.entry('H', "HH24"),   // 时，24 小时制
            Map.entry('k', "HH24"),
            Map.entry('h', "HH12"),   // 时，12 小时制
            Map.entry('I', "HH12"),
            Map.entry('i', "MI"),     // 分（注意不是月份）
            Map.entry('s', "SS"),     // 秒
            Map.entry('S', "SS"),
            Map.entry('M', "MONTH"),  // 月份全称
            Map.entry('b', "MON"),    // 月份缩写
            Map.entry('W', "DAY"),    // 星期全称
            Map.entry('a', "DY"),     // 星期缩写
            Map.entry('j', "DDD"),    // 年内第几天
            Map.entry('p', "AM"),     // 上下午标记
            Map.entry('T', "HH24:MI:SS")
    );

    /**
     * 在 TO_CHAR 格式串里可以裸着出现的标点。其余字面文本（例如中文的"年月日"、
     * ISO 8601 里的 {@code T}）在 Oracle 系必须用双引号括起来，否则报格式串非法；
     * PG 系同样接受双引号形式，所以统一加引号对两族都安全。
     */
    private static final String TO_CHAR_BARE_PUNCTUATION = "-/,.;: ";

    /**
     * 把 SQL 里 DATE_FORMAT 调用的格式串翻成 TO_CHAR 的写法，函数名留给通用映射去改。
     */
    private String convertDateFormatPatterns(String sql) {
        return replaceOutsideLiterals(sql, DATE_FORMAT_CALL, m -> {
            String args = m.group(1);
            Matcher literal = TRAILING_FORMAT_LITERAL.matcher(args);
            // 第二参不是字面量（变量、MyBatis 占位符、函数调用）时无从下手，原样保留不瞎猜
            if (!literal.find()) return m.group();

            String translated = translateMysqlDatePattern(literal.group(1));
            if (translated == null || translated.equals(literal.group(1))) return m.group();

            int argsOffset = m.start(1) - m.start();
            return m.group().substring(0, argsOffset + literal.start())
                    + "'" + translated + "'"
                    + m.group().substring(argsOffset + literal.end());
        });
    }

    /**
     * 翻译单个格式串。返回 {@code null} 表示放弃翻译（调用方保留原样）。
     */
    public static String translateMysqlDatePattern(String pattern) {
        // 原串已含双引号，再套一层引用规则容易产出畸形格式串，直接不碰
        if (pattern.indexOf('"') >= 0) return null;

        StringBuilder out = new StringBuilder(pattern.length() + 8);
        StringBuilder literalRun = new StringBuilder();

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (c == '%' && i + 1 < pattern.length()) {
                char spec = pattern.charAt(++i);
                String mapped = MYSQL_DATE_SPECIFIERS.get(spec);
                if (mapped != null) {
                    flushLiteralRun(out, literalRun);
                    out.append(mapped);
                } else if (spec == '%') {
                    literalRun.append('%');          // %% 是转义出来的一个 % 字面量
                } else {
                    literalRun.append('%').append(spec);  // 不认识的格式符原样留下，宁可显眼也不猜
                }
                continue;
            }

            if (TO_CHAR_BARE_PUNCTUATION.indexOf(c) >= 0) {
                flushLiteralRun(out, literalRun);
                out.append(c);
            } else {
                literalRun.append(c);
            }
        }

        flushLiteralRun(out, literalRun);
        return out.toString();
    }

    /** 把攒下的字面文本作为一段带双引号的常量写出。 */
    private static void flushLiteralRun(StringBuilder out, StringBuilder literalRun) {
        if (literalRun.length() == 0) return;
        out.append('"').append(literalRun).append('"');
        literalRun.setLength(0);
    }

    /** 一次 TO_CHAR 调用。 */
    private static final Pattern TO_CHAR_CALL = Pattern.compile(
            "\\bTO_CHAR\\s*\\(([^()]*)\\)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * TO_CHAR 格式符 -> MySQL DATE_FORMAT 格式符，即 {@link #MYSQL_DATE_SPECIFIERS} 的逆表。
     * <p>用 List 而不是 Map：必须按长度从长到短依次匹配，否则 {@code HH24} 会先被 {@code HH} 命中、
     * {@code MONTH} 会先被 {@code MON} 命中。
     */
    private static final List<Map.Entry<String, String>> TO_CHAR_SPECIFIERS = List.of(
            Map.entry("HH24", "%H"),
            Map.entry("HH12", "%h"),
            Map.entry("MONTH", "%M"),
            Map.entry("YYYY", "%Y"),
            Map.entry("DDD", "%j"),
            Map.entry("MON", "%b"),
            Map.entry("DAY", "%W"),
            Map.entry("SS", "%s"),
            Map.entry("MI", "%i"),
            Map.entry("MM", "%m"),
            Map.entry("DD", "%d"),
            Map.entry("YY", "%y"),
            Map.entry("HH", "%h"),     // 不带 12/24 后缀的 HH，Oracle 语义是 12 小时制
            Map.entry("DY", "%a"),
            Map.entry("AM", "%p"),
            Map.entry("PM", "%p")
    );

    /**
     * 把 SQL 里 TO_CHAR 调用的格式串翻成 DATE_FORMAT 的写法，函数名留给通用映射去改。
     */
    private String convertToCharPatterns(String sql) {
        return replaceOutsideLiterals(sql, TO_CHAR_CALL, m -> {
            String args = m.group(1);
            Matcher literal = TRAILING_FORMAT_LITERAL.matcher(args);
            // 单参的 TO_CHAR（纯粹的数字/日期转字符串）没有格式串，交给通用映射只改函数名
            if (!literal.find()) return m.group();

            String translated = translateToCharPattern(literal.group(1));
            if (translated == null || translated.equals(literal.group(1))) return m.group();

            int argsOffset = m.start(1) - m.start();
            return m.group().substring(0, argsOffset + literal.start())
                    + "'" + translated + "'"
                    + m.group().substring(argsOffset + literal.end());
        });
    }

    /**
     * 翻译单个 TO_CHAR 格式串成 MySQL 写法。返回 {@code null} 表示放弃翻译（调用方保留原样）。
     */
    public static String translateToCharPattern(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() + 8);
        int i = 0;

        while (i < pattern.length()) {
            char c = pattern.charAt(i);

            // 双引号括起来的是字面文本（TO_CHAR 里中文"年月日"必须这么写），去掉引号原样输出
            if (c == '"') {
                int end = pattern.indexOf('"', i + 1);
                if (end < 0) return null;       // 引号不成对，格式串本身就是坏的，不碰
                appendMysqlLiteral(out, pattern, i + 1, end);
                i = end + 1;
                continue;
            }

            String matched = null;
            for (Map.Entry<String, String> spec : TO_CHAR_SPECIFIERS) {
                if (pattern.regionMatches(true, i, spec.getKey(), 0, spec.getKey().length())) {
                    matched = spec.getValue();
                    i += spec.getKey().length();
                    break;
                }
            }
            if (matched != null) {
                out.append(matched);
                continue;
            }

            appendMysqlLiteral(out, pattern, i, i + 1);
            i++;
        }

        return out.toString();
    }

    /** 输出一段字面文本，其中的 % 要转义成 %%，否则会被 MySQL 当成格式符。 */
    private static void appendMysqlLiteral(StringBuilder out, String source, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = source.charAt(i);
            if (c == '%') out.append("%%");
            else out.append(c);
        }
    }

    private String convertTypes(String sql, DialectConfig config) {
        if (config.types == null || config.types.isEmpty()) {
            return sql;
        }

        boolean[] masked = maskLiterals(sql);
        boolean[] typeSlot = collectTypeSlots(sql, masked);

        Matcher matcher = config.typePattern().matcher(sql);
        StringBuilder out = new StringBuilder(sql.length());
        int last = 0;
        boolean changed = false;

        while (matcher.find()) {
            if (masked[matcher.start()]) continue;      // 字面量/注释/占位符内
            if (!typeSlot[matcher.start()]) continue;   // 不在类型声明位置 => 这是标识符
            String target = config.types.get(matcher.group().toUpperCase(Locale.ROOT));
            if (target == null) continue;

            out.append(sql, last, matcher.start()).append(target);
            last = matcher.end();
            changed = true;
        }

        if (!changed) return sql;
        out.append(sql, last, sql.length());
        return out.toString();
    }

    // ==================== 类型声明位置识别 ====================
    //
    // TEXT / INT / DATE / DATETIME / DOUBLE 既是类型名，也是极常见的列名。
    // 只靠"是不是在字面量里"无法区分，于是 SELECT text FROM notes 会被改成
    // SELECT CLOB FROM notes —— 列不存在，查询直接失败。
    //
    // 因此改为白名单：只有落在**类型声明槽位**上的词才替换，其余一律视为标识符。
    // 覆盖 CREATE TABLE 列定义、CAST(x AS t)、PostgreSQL 的 x::t、ALTER TABLE 的
    // ADD / MODIFY / CHANGE / ALTER COLUMN 四种子句。
    //
    // 这里用词法扫描而非 JSqlParser AST，原因有两个（都已实测确认）：
    //   1. JSqlParser 4.9 的 ColumnDefinition / ColDataType 没有实现 ASTNodeAccess，
    //      Token 也只有行列号、没有绝对字符偏移，无法把类型节点映射回原文位置——
    //      而"按偏移写回原文"是保形改造的前提。
    //   2. 含 #{} / 动态标签的 MyBatis 片段根本无法被解析，恰好是保形改造的主场景。

    /** 标记出每个字符是否属于"类型声明槽位"。 */
    private boolean[] collectTypeSlots(String sql, boolean[] masked) {
        boolean[] slot = new boolean[sql.length()];
        markCreateTableTypeSlots(sql, masked, slot);
        markCastTypeSlots(sql, masked, slot);
        markCastOperatorTypeSlots(sql, masked, slot);
        markAlterTableTypeSlots(sql, masked, slot);
        markConvertTypeSlots(sql, masked, slot);
        markDeclareTypeSlots(sql, masked, slot);
        return slot;
    }

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "\\bCREATE\\s+(?:(?:GLOBAL|LOCAL|TEMP|TEMPORARY|UNLOGGED|EXTERNAL)\\s+)*TABLE\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE_PATTERN =
            Pattern.compile("\\bALTER\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_CLAUSE_PATTERN = Pattern.compile(
            "\\b(ADD|MODIFY|CHANGE|ALTER)\\s+(?:COLUMN\\s+)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern CAST_PATTERN =
            Pattern.compile("\\b(?:CAST|TRY_CAST|SAFE_CAST)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 表级约束：这些开头的项不是列定义，第二个词也就不是类型。 */
    private static final Set<String> TABLE_LEVEL_CONSTRAINTS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN",
            "CHECK", "FULLTEXT", "SPATIAL", "EXCLUDE", "PERIOD");

    private void markCreateTableTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int open = indexOfUnmasked(sql, '(', matcher.end(), sql.length(), masked);
            if (open < 0) continue;
            // CREATE TABLE x AS SELECT ...：括号里是查询而不是列定义
            if (containsKeyword(sql, matcher.end(), open, "AS", masked)) continue;

            int close = matchingParen(sql, open, masked);
            if (close < 0) continue;
            markColumnDefinitionTypes(sql, open + 1, close, masked, slot);
        }
    }

    /** 按顶层逗号切开列定义列表，逐项标记类型槽位。 */
    private void markColumnDefinitionTypes(String sql, int start, int end,
                                           boolean[] masked, boolean[] slot) {
        for (int[] item : splitTopLevel(sql, start, end, masked, ',')) {
            markColumnType(sql, item[0], item[1], masked, slot);
        }
    }

    /** 单个列定义形如 {@code name TYPE(n) [约束...]}：跳过列名，标记第二个词。 */
    private void markColumnType(String sql, int start, int end, boolean[] masked, boolean[] slot) {
        int nameStart = skipWhitespace(sql, start, end);
        int nameEnd = tokenEnd(sql, nameStart, end);
        if (nameEnd <= nameStart) return;

        String firstWord = sql.substring(nameStart, nameEnd).toUpperCase(Locale.ROOT);
        if (TABLE_LEVEL_CONSTRAINTS.contains(firstWord)) return;

        markToken(sql, nameEnd, end, masked, slot);
    }

    private void markCastTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = CAST_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int open = matcher.end() - 1;
            int close = matchingParen(sql, open, masked);
            if (close < 0) continue;

            // 只认 CAST 括号内顶层的 AS；SELECT a AS text 那种别名里的 AS 不会走到这里
            int afterAs = lastTopLevelKeywordEnd(sql, open + 1, close, "AS", masked);
            if (afterAs < 0) continue;
            markToken(sql, afterAs, close, masked, slot);
        }
    }

    /** PostgreSQL / 高斯的 {@code expr::type}。 */
    private void markCastOperatorTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        for (int i = 0; i + 1 < sql.length(); i++) {
            if (masked[i]) continue;
            if (sql.charAt(i) != ':' || sql.charAt(i + 1) != ':') continue;
            markToken(sql, i + 2, sql.length(), masked, slot);
            i++;
        }
    }

    private void markAlterTableTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher outer = ALTER_TABLE_PATTERN.matcher(sql);
        while (outer.find()) {
            if (masked[outer.start()]) continue;

            int stmtEnd = indexOfUnmasked(sql, ';', outer.end(), sql.length(), masked);
            if (stmtEnd < 0) stmtEnd = sql.length();

            Matcher clause = ALTER_CLAUSE_PATTERN.matcher(sql);
            clause.region(outer.end(), stmtEnd);
            while (clause.find()) {
                if (masked[clause.start()]) continue;
                markAlterClauseType(sql, clause, stmtEnd, masked, slot);
            }
        }
    }

    private void markAlterClauseType(String sql, Matcher clause, int stmtEnd,
                                     boolean[] masked, boolean[] slot) {
        int cursor = clause.end();

        // ADD CONSTRAINT / ADD PRIMARY KEY (...) 之类不是列定义
        int firstStart = skipWhitespace(sql, cursor, stmtEnd);
        int firstEnd = tokenEnd(sql, firstStart, stmtEnd);
        if (firstEnd <= firstStart) return;
        if (TABLE_LEVEL_CONSTRAINTS.contains(
                sql.substring(firstStart, firstEnd).toUpperCase(Locale.ROOT))) {
            return;
        }

        // CHANGE 是 old_name new_name TYPE，要跳两个标识符；其余跳一个
        int names = "CHANGE".equalsIgnoreCase(clause.group(1)) ? 2 : 1;
        cursor = firstEnd;
        for (int k = 1; k < names && cursor < stmtEnd; k++) {
            cursor = tokenEnd(sql, skipWhitespace(sql, cursor, stmtEnd), stmtEnd);
        }

        // PostgreSQL: ALTER COLUMN c TYPE bigint / ALTER COLUMN c SET DATA TYPE bigint
        cursor = skipKeywords(sql, cursor, stmtEnd, "SET", "DATA", "TYPE");

        markToken(sql, cursor, stmtEnd, masked, slot);
    }

    private static final Pattern CONVERT_PATTERN =
            Pattern.compile("\\bCONVERT\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * 用于**判别**（而非替换）的类型名词表：比方言映射表宽得多，
     * 涵盖 CHAR / SIGNED / UNSIGNED 等不参与转换但确实是类型的词。
     * <p>判别越宽越安全：只要两侧都可能是类型名就放弃，避免把列名当类型改掉。
     */
    private static final Set<String> KNOWN_TYPE_WORDS = Set.of(
            "CHAR", "NCHAR", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHARACTER",
            "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB",
            "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY", "RAW", "BYTEA",
            "INT", "INT2", "INT4", "INT8", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT", "BIGINT",
            "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "REAL", "DOUBLE", "PRECISION",
            "BIT", "BOOLEAN", "BOOL", "SIGNED", "UNSIGNED",
            "DATE", "TIME", "DATETIME", "DATETIME2", "TIMESTAMP", "YEAR", "INTERVAL",
            "JSON", "JSONB", "UUID", "XML", "MONEY", "SERIAL", "BIGSERIAL");

    /**
     * {@code CONVERT()} 的参数顺序在不同数据库里是**相反**的：
     * <pre>
     *   MySQL       CONVERT(expr, TYPE)
     *   SQL Server  CONVERT(TYPE, expr [, style])
     * </pre>
     * 本工具只接收目标库、不接收源库，无法靠方言判断，因此改为看内容：
     * 哪一侧像类型名而另一侧不像，就认那一侧是类型；两侧都像则放弃——
     * 宁可漏改，也不能把 {@code CONVERT(text, CHAR)} 里的列名 text 改成 CLOB。
     * <p>MySQL 的 {@code CONVERT(expr USING utf8mb4)} 转的是字符集，不含类型，直接跳过。
     */
    private void markConvertTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = CONVERT_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int open = matcher.end() - 1;
            int close = matchingParen(sql, open, masked);
            if (close < 0) continue;
            if (findKeyword(sql, open + 1, close, "USING", masked, true, true) >= 0) continue;

            List<int[]> args = splitTopLevel(sql, open + 1, close, masked, ',');
            if (args.size() < 2) continue;

            int[] first = args.get(0);
            int[] second = args.get(1);
            boolean firstIsType = isTypeLikeArgument(sql, first, masked);
            boolean secondIsType = isTypeLikeArgument(sql, second, masked);

            if (firstIsType && !secondIsType) {
                markToken(sql, first[0], first[1], masked, slot);      // SQL Server 形式
            } else if (secondIsType && !firstIsType) {
                markToken(sql, second[0], second[1], masked, slot);    // MySQL 形式
            }
            // 两侧都像类型名 => 无法判断，一个都不动
        }
    }

    /** 该参数是否形如一个纯类型：单个类型名，后面最多跟一组长度/精度括号。 */
    private boolean isTypeLikeArgument(String sql, int[] span, boolean[] masked) {
        int start = skipWhitespace(sql, span[0], span[1]);
        int end = tokenEnd(sql, start, span[1]);
        if (end <= start || masked[start]) return false;
        if (!KNOWN_TYPE_WORDS.contains(sql.substring(start, end).toUpperCase(Locale.ROOT))) {
            return false;
        }

        int rest = skipWhitespace(sql, end, span[1]);
        if (rest >= span[1]) return true;                 // CHAR
        if (sql.charAt(rest) != '(') return false;        // 还有别的东西，不是纯类型
        int close = matchingParen(sql, rest, masked);
        return close >= 0 && skipWhitespace(sql, close + 1, span[1]) >= span[1];   // DECIMAL(10,2)
    }

    private static final Pattern DECLARE_PATTERN =
            Pattern.compile("\\bDECLARE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 存储过程里的变量声明。三种写法的**区间边界不同**，必须先判形再切分：
     * <pre>
     *   T-SQL     DECLARE @a INT, @b TEXT;          -- 逗号分隔，止于分号
     *   PL/SQL    DECLARE a INT; b TEXT; BEGIN ...  -- 分号分隔，止于 BEGIN
     *   单条       DECLARE v INT;                    -- 止于分号
     * </pre>
     * 判形依据：T-SQL 变量以 {@code @} 开头。若按 PL/SQL 那样一律切到 BEGIN，
     * T-SQL 的 {@code DECLARE @a INT; SELECT text FROM t} 会把 {@code text FROM t}
     * 当成一条声明，把列名 text 当作类型改掉。
     */
    private void markDeclareTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = DECLARE_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int bodyStart = matcher.end();
            int semicolon = indexOfUnmasked(sql, ';', bodyStart, sql.length(), masked);
            int firstTokenStart = skipWhitespace(sql, bodyStart, sql.length());
            boolean tsqlStyle = firstTokenStart < sql.length() && sql.charAt(firstTokenStart) == '@';

            int end;
            char[] separators;
            if (tsqlStyle) {
                end = (semicolon < 0) ? sql.length() : semicolon;
                separators = new char[]{','};
            } else {
                int begin = findKeyword(sql, bodyStart, sql.length(), "BEGIN", masked, true, true);
                if (begin >= 0) {
                    end = begin;                              // PL/SQL 声明块
                    separators = new char[]{';', ','};
                } else {
                    end = (semicolon < 0) ? sql.length() : semicolon;
                    separators = new char[]{','};
                }
            }

            // 游标声明后面跟的是完整查询，不是类型声明，整段跳过
            if (findKeyword(sql, bodyStart, end, "CURSOR", masked, true, true) >= 0) continue;

            for (int[] item : splitTopLevel(sql, bodyStart, end, masked, separators)) {
                markDeclaredVariableType(sql, item[0], item[1], masked, slot);
            }
        }
    }

    /** 单条声明形如 {@code name TYPE [:= 默认值]}：跳过变量名，标记第二个词。 */
    private void markDeclaredVariableType(String sql, int start, int end,
                                          boolean[] masked, boolean[] slot) {
        int nameStart = skipWhitespace(sql, start, end);
        int nameEnd = tokenEnd(sql, nameStart, end);
        if (nameEnd <= nameStart) return;
        markToken(sql, nameEnd, end, masked, slot);
    }

    // ---------- 词法游标工具 ----------

    /** 按顶层分隔符切分区间，返回每段的 {@code [start, end)}。括号内与掩码内的分隔符不算。 */
    private List<int[]> splitTopLevel(String sql, int start, int end, boolean[] masked, char... separators) {
        List<int[]> parts = new ArrayList<>();
        int partStart = start;
        int depth = 0;
        for (int i = start; i < end; i++) {
            if (masked[i]) continue;
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && isSeparator(c, separators)) {
                parts.add(new int[]{partStart, i});
                partStart = i + 1;
            }
        }
        parts.add(new int[]{partStart, end});
        return parts;
    }

    private boolean isSeparator(char c, char[] separators) {
        for (char s : separators) {
            if (c == s) return true;
        }
        return false;
    }

    /** 从 {@code from} 起跳过空白后标记一个完整 token 为类型槽位。 */
    private void markToken(String sql, int from, int end, boolean[] masked, boolean[] slot) {
        int start = skipWhitespace(sql, from, end);
        int stop = tokenEnd(sql, start, end);
        if (stop <= start) return;
        if (masked[start]) return;   // 引号标识符不是类型
        for (int i = start; i < stop; i++) {
            slot[i] = true;
        }
    }

    /**
     * 跳过空白**和注释**。
     * <p>注释必须一起跳过：真实项目的建表语句几乎每列都带 {@code -- 注释}，
     * 若只跳空白，{@code -- 主键\n  id INT} 里的 INT 就会被漏掉（漏改不会改坏文件，但会漏迁移）。
     */
    private int skipWhitespace(String sql, int i, int end) {
        while (i < end) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < end && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = (newline < 0 || newline >= end) ? end : newline + 1;
            } else if (c == '/' && i + 1 < end && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = (close < 0 || close + 2 > end) ? end : close + 2;
            } else {
                break;
            }
        }
        return i;
    }

    /** 消费一个标识符 / 关键字 token；遇到引号标识符则整段消费。 */
    private int tokenEnd(String sql, int i, int end) {
        if (i >= end) return i;
        if (isQuote(sql.charAt(i))) {
            return Math.min(findQuoteEnd(sql, i), end);
        }
        int j = i;
        while (j < end && isIdentifierChar(sql.charAt(j))) j++;
        return j;
    }

    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@';
    }

    /** 依次跳过给定的可选关键字（出现即跳过，不出现就原地返回）。 */
    private int skipKeywords(String sql, int i, int end, String... keywords) {
        for (String keyword : keywords) {
            int start = skipWhitespace(sql, i, end);
            int stop = tokenEnd(sql, start, end);
            if (stop > start && sql.substring(start, stop).equalsIgnoreCase(keyword)) {
                i = stop;
            }
        }
        return i;
    }

    private int indexOfUnmasked(String sql, char target, int from, int end, boolean[] masked) {
        for (int i = from; i < end; i++) {
            if (!masked[i] && sql.charAt(i) == target) return i;
        }
        return -1;
    }

    /** 找到与 {@code open} 处左括号配对的右括号下标；找不到返回 -1。 */
    private int matchingParen(String sql, int open, boolean[] masked) {
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            if (masked[i]) continue;
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private boolean containsKeyword(String sql, int from, int end, String keyword, boolean[] masked) {
        return findKeyword(sql, from, end, keyword, masked, false, true) >= 0;
    }

    /** 返回区间内**最后一个**顶层 keyword 的结束位置（供 CAST 的 AS 使用）。 */
    private int lastTopLevelKeywordEnd(String sql, int from, int end, String keyword, boolean[] masked) {
        int start = findKeyword(sql, from, end, keyword, masked, true, false);
        return start < 0 ? -1 : start + keyword.length();
    }

    /**
     * 查找关键字 token 的**起始**下标；找不到返回 -1。
     *
     * @param topLevelOnly 只认括号深度 0 处的匹配
     * @param wantFirst    true 取第一个匹配，false 取最后一个
     */
    private int findKeyword(String sql, int from, int end, String keyword,
                            boolean[] masked, boolean topLevelOnly, boolean wantFirst) {
        int found = -1;
        int depth = 0;
        int i = from;
        while (i < end) {
            if (masked[i]) { i++; continue; }
            char c = sql.charAt(i);
            if (c == '(') { depth++; i++; continue; }
            if (c == ')') { depth--; i++; continue; }
            if (!isIdentifierChar(c)) { i++; continue; }

            int stop = tokenEnd(sql, i, end);
            if ((!topLevelOnly || depth == 0)
                    && sql.substring(i, stop).equalsIgnoreCase(keyword)) {
                if (wantFirst) return i;
                found = i;
            }
            i = Math.max(stop, i + 1);
        }
        return found;
    }

    // ==================== 字面量保护 ====================
    //
    // 类型/函数替换都是对整条 SQL 做正则替换。如果不区分字符串字面量，
    // WHERE kind = 'text' 会被改成 WHERE kind = 'CLOB'，
    // 这不是语法错误而是**静默改变业务语义**，比报错危险得多。
    // 引号标识符（"text"、`text`）和注释同理，都不该被改动。

    /**
     * 只替换落在字面量/注释之外的匹配。
     * <p>掩码基于传入的 {@code sql} 一次算好，替换结果写入新串，因此下标始终有效。
     */
    private String replaceOutsideLiterals(String sql, Pattern pattern, String replacement) {
        return replaceOutsideLiterals(sql, pattern, m -> replacement);
    }

    /**
     * 只替换落在字面量/注释之外的匹配，替换文本由 {@code replacer} 依据匹配结果生成。
     * <p>单趟扫描：不会像"每次替换后从头重新匹配"那样退化成 O(n²)，
     * 也不会在替换结果又能命中同一模式时死循环。
     */
    private String replaceOutsideLiterals(String sql, Pattern pattern,
                                          java.util.function.Function<Matcher, String> replacer) {
        boolean[] masked = maskLiterals(sql);
        Matcher matcher = pattern.matcher(sql);

        StringBuilder out = new StringBuilder(sql.length());
        int last = 0;
        boolean changed = false;

        while (matcher.find()) {
            if (masked[matcher.start()]) continue;   // 命中在字面量/注释内，跳过
            out.append(sql, last, matcher.start()).append(replacer.apply(matcher));
            last = matcher.end();
            changed = true;
        }

        if (!changed) return sql;
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * 标记出不应参与替换的字符位置：字符串字面量、引号标识符（{@code "x"} / {@code `x`}）、
     * 行注释（{@code --}）、块注释（{@code /*}），以及自动改造场景下的
     * MyBatis 占位符（{@code #{x}} / {@code ${x}}）与 XML 标签。
     * <p>后两类是为"保形转换"服务的：转换结果要原样写回源文件，
     * {@code #{text}} 若被改成 {@code #{CLOB}}、{@code <if test="text != null">} 若被改动，
     * mapper 就废了。
     */
    private boolean[] maskLiterals(String sql) {
        boolean[] masked = new boolean[sql.length()];
        int n = sql.length();
        int i = 0;

        while (i < n) {
            char c = sql.charAt(i);
            int end;

            if (c == '\\' && i + 1 < n && !isQuote(sql.charAt(i + 1))) {
                // Java 原文里的 \n \t \\ 等转义对：整体跳过，避免 n/t 之类被误当关键字起点
                i += 2;
                continue;
            } else if (c == '\\' && i + 1 < n) {
                // \" 或 \'：反斜杠只是 Java 层转义，后面那个引号仍是 SQL 的引号
                end = findQuoteEnd(sql, i + 1);
            } else if (isQuote(c)) {
                end = findQuoteEnd(sql, i);
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                end = sql.indexOf('\n', i);
                if (end < 0) end = n;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                end = (close < 0) ? n : close + 2;
            } else {
                i++;
                continue;
            }

            for (int j = i; j < end; j++) {
                masked[j] = true;
            }
            i = end;
        }

        maskAll(MYBATIS_PLACEHOLDER_PATTERN, sql, masked);
        maskAll(XML_TAG_PATTERN, sql, masked);

        return masked;
    }

    private boolean isQuote(char c) {
        return c == '\'' || c == '"' || c == '`';
    }

    /** MyBatis 占位符。普通 SQL 里不会出现这种写法，因此可以无条件保护。 */
    private static final Pattern MYBATIS_PLACEHOLDER_PATTERN = Pattern.compile(
            "[#$]\\{[^}]*}");

    /**
     * XML 标签。刻意写得保守：要求 {@code <} 后紧跟字母或 {@code /}，
     * 这样 {@code a < 5}、{@code a <> b}、{@code a <= 5} 都不会被误判成标签。
     */
    private static final Pattern XML_TAG_PATTERN = Pattern.compile(
            "</?[A-Za-z][\\w:.-]*(?:\\s[^<>]*)?/?>");

    private void maskAll(Pattern pattern, String sql, boolean[] masked) {
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            for (int j = m.start(); j < m.end(); j++) {
                masked[j] = true;
            }
        }
    }

    /**
     * 返回引号片段的结束下标（不含）。重复引号（{@code ''}、{@code ""}）是转义形式，不算闭合；
     * 片段内的 {@code \x} 转义对整体跳过，因此 {@code 'it\'s'} 不会被提前判定结束。
     * 引号未闭合时把剩余内容整体视为字面量——宁可漏改，也不能改坏。
     */
    private int findQuoteEnd(String sql, int start) {
        char quote = sql.charAt(start);
        int n = sql.length();
        int i = start + 1;

        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;     // 'it\'s' 这类转义引号
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;     // '' 表示一个引号字符本身
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private String convertSyntax(String sql, DialectConfig config) {
        String result = sql;

        // 处理 LIMIT 子句
        result = convertLimit(result, config);

        // 处理 AUTO_INCREMENT
        String autoIncSyntax = config.syntax.get("auto_increment");
        if (autoIncSyntax != null && !autoIncSyntax.isEmpty()) {
            Pattern autoIncPattern = Pattern.compile(
                "\\bAUTO_INCREMENT\\b",
                Pattern.CASE_INSENSITIVE
            );
            result = replaceOutsideLiterals(result, autoIncPattern, autoIncSyntax);
        }

        return result;
    }

    /** 匹配达梦/Oracle 系的 ROWNUM 限行，前面可能挂着 WHERE 或 AND。 */
    private static final Pattern ROWNUM_PATTERN = Pattern.compile(
            "\\s*\\b(?:WHERE|AND)\\s+ROWNUM\\s*<=?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
    );

    /** 匹配 PG/金仓系的 FETCH FIRST n ROWS ONLY。 */
    private static final Pattern FETCH_FIRST_PATTERN = Pattern.compile(
            "\\bFETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY",
            Pattern.CASE_INSENSITIVE
    );

    /** 匹配达梦的自增列写法 IDENTITY(1,1)。 */
    private static final Pattern IDENTITY_PATTERN = Pattern.compile(
            "\\bIDENTITY\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 限行语法的转换。目标是 MySQL 系时先把国产库/Oracle 的写法收敛回 LIMIT，
     * 再走通用的 LIMIT -> 目标写法（此时是恒等，等于原样保留）。
     */
    private String convertLimit(String sql, DialectConfig config) {
        String result = sql;

        if (config.reverseToMysql) {
            // WHERE ROWNUM <= 10 -> LIMIT 10；连着前面的 WHERE/AND 一起吃掉，
            // 否则会剩下一个空的 WHERE 或者 "status = 1 AND LIMIT 10" 这种非法语句。
            result = replaceOutsideLiterals(result, ROWNUM_PATTERN, m -> {
                boolean startsWithWhere = m.group().trim().regionMatches(true, 0, "WHERE", 0, 5);
                // 原本是 AND ROWNUM 说明同层已有 WHERE 条件，LIMIT 要挪到句尾才合法，
                // 这种情况交给人工，不做半对半错的改写
                return startsWithWhere ? " LIMIT " + m.group(1) : m.group();
            });
            result = replaceOutsideLiterals(result, FETCH_FIRST_PATTERN, m -> "LIMIT " + m.group(1));
            result = replaceOutsideLiterals(result, IDENTITY_PATTERN, "AUTO_INCREMENT");
        }

        if (config.sqlServerTop) {
            return convertSqlServerLimit(result);
        }

        return applyLimitSyntax(result, config.syntax.get("limit"));
    }

    /** 匹配 LIMIT n 或 LIMIT n OFFSET m */
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 把 LIMIT n 替换成目标方言的写法。
     * <p>
     * 达梦用的是 {@code WHERE ROWNUM <= n}：如果原句在同一层级上已经有 WHERE 子句，
     * 直接拼上去会得到 {@code ... WHERE status = 1 WHERE ROWNUM <= 3} 这种双 WHERE 的非法 SQL，
     * 此时必须改用 {@code AND}。
     */
    private String applyLimitSyntax(String sql, String limitSyntax) {
        if (limitSyntax == null) return sql;

        // 只认字面量/注释之外的第一个 LIMIT
        boolean[] masked = maskLiterals(sql);
        Matcher limitMatcher = LIMIT_PATTERN.matcher(sql);
        boolean found = false;
        while (limitMatcher.find()) {
            if (!masked[limitMatcher.start()]) {
                found = true;
                break;
            }
        }
        if (!found) return sql;

        String replacement = limitSyntax.replace("{n}", limitMatcher.group(1));

        // 目标写法本身以 WHERE 开头，且原句同层已有 WHERE ⇒ 换成 AND
        if (startsWithWhere(replacement)
                && hasTopLevelWhere(sql.substring(0, limitMatcher.start()))) {
            replacement = "AND" + replacement.substring("WHERE".length());
        }

        return sql.substring(0, limitMatcher.start())
                + replacement
                + sql.substring(limitMatcher.end());
    }

    /**
     * SQL Server 的 LIMIT 改写。
     * <p>无 OFFSET 时 SQL Server 2012 以前的等价写法是 {@code SELECT TOP n}，要把 TOP
     * 提到 SELECT 之后；带 OFFSET 时用 2012+ 的 {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY}
     * （位置与 LIMIT 一致，直接替换），该语法强制要求 ORDER BY，未带时补一个不影响结果的占位。
     */
    private String convertSqlServerLimit(String sql) {
        boolean[] masked = maskLiterals(sql);
        Matcher m = LIMIT_PATTERN.matcher(sql);
        int start = -1;
        while (m.find()) {
            if (!masked[m.start()]) { start = m.start(); break; }
        }
        if (start < 0) return sql;

        String n = m.group(1);
        String offset = m.group(2);
        String head = sql.substring(0, start).stripTrailing();
        String tail = sql.substring(m.end());

        if (offset != null) {
            String orderBy = hasTopLevelOrderBy(head) ? "" : " ORDER BY (SELECT NULL)";
            return head + orderBy + " OFFSET " + offset + " ROWS FETCH NEXT " + n + " ROWS ONLY" + tail;
        }
        return insertSelectTop(head, n) + tail;
    }

    /** 在首个顶层 SELECT 之后插入 TOP n。 */
    private String insertSelectTop(String sql, String n) {
        boolean[] masked = maskLiterals(sql);
        int selStart = findKeyword(sql, 0, sql.length(), "SELECT", masked, true, true);
        if (selStart < 0) return sql;
        int after = selStart + "SELECT".length();
        return sql.substring(0, after) + " TOP " + n + sql.substring(after);
    }

    /** 顶层是否存在 ORDER BY 子句（括号内子查询/函数参数里的 ORDER 不算）。 */
    private boolean hasTopLevelOrderBy(String sql) {
        int depth = 0;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) { if (c == '\'') inSingle = false; continue; }
            if (inDouble) { if (c == '"') inDouble = false; continue; }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { if (depth > 0) depth--; continue; }
            if (depth == 0 && (c == 'O' || c == 'o')
                    && sql.regionMatches(true, i, "ORDER", 0, 5)
                    && isWordBoundary(sql, i - 1) && isWordBoundary(sql, i + 5)) {
                int j = i + 5;
                while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;
                if (sql.regionMatches(true, j, "BY", 0, 2)) return true;
            }
        }
        return false;
    }

    private boolean startsWithWhere(String s) {
        return s.length() >= 5 && s.regionMatches(true, 0, "WHERE", 0, 5);
    }

    /**
     * 判断 SQL 片段里是否存在「顶层」WHERE 子句。
     * 括号内（子查询、函数参数）的 WHERE 不算——那种情况下拼 WHERE 才是对的。
     */
    private boolean hasTopLevelWhere(String prefix) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
                continue;
            }
            if (c == '\'') { inSingleQuote = true; continue; }
            if (c == '"') { inDoubleQuote = true; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { if (depth > 0) depth--; continue; }

            if (depth == 0 && (c == 'W' || c == 'w')
                    && prefix.regionMatches(true, i, "WHERE", 0, 5)
                    && isWordBoundary(prefix, i - 1)
                    && isWordBoundary(prefix, i + 5)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWordBoundary(String s, int index) {
        if (index < 0 || index >= s.length()) return true;
        char c = s.charAt(index);
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    private DialectConfig createGaussDbConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "COALESCE"),
            Map.entry("DATE_FORMAT", "TO_CHAR"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "CLOB"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INTEGER"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "NUMERIC"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "SMALLINT"),
            Map.entry("MEDIUMINT", "INTEGER"),
            Map.entry("LONGTEXT", "CLOB"),
            Map.entry("MEDIUMTEXT", "CLOB"),
            Map.entry("DOUBLE", "DOUBLE PRECISION"),
            Map.entry("FLOAT", "REAL")
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY",
            "auto_increment", "",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createDamengConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "SYSDATE"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "NVL"),
            Map.entry("DATE_FORMAT", "TO_CHAR"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "CLOB"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "INT"),
            Map.entry("LONGTEXT", "CLOB"),
            Map.entry("MEDIUMTEXT", "CLOB"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "WHERE ROWNUM <= {n}",
            "auto_increment", "IDENTITY(1,1)",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createKingbaseConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "COALESCE"),
            Map.entry("DATE_FORMAT", "TO_CHAR"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "TEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INTEGER"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "NUMERIC"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "SMALLINT"),
            Map.entry("MEDIUMINT", "INTEGER"),
            Map.entry("LONGTEXT", "TEXT"),
            Map.entry("MEDIUMTEXT", "TEXT"),
            Map.entry("DOUBLE", "DOUBLE PRECISION"),
            Map.entry("FLOAT", "REAL")
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY",
            "auto_increment", "",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createOceanBaseConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "NVL"),
            Map.entry("DATE_FORMAT", "DATE_FORMAT"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "LONGTEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "MEDIUMINT"),
            Map.entry("LONGTEXT", "LONGTEXT"),
            Map.entry("MEDIUMTEXT", "MEDIUMTEXT"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "LIMIT {n}",
            "auto_increment", "AUTO_INCREMENT",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createTiDbConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "IFNULL"),
            Map.entry("DATE_FORMAT", "DATE_FORMAT"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "TEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "MEDIUMINT"),
            Map.entry("LONGTEXT", "LONGTEXT"),
            Map.entry("MEDIUMTEXT", "MEDIUMTEXT"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "LIMIT {n}",
            "auto_increment", "AUTO_INCREMENT",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createGBaseConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.of(
            "NOW", "CURRENT_TIMESTAMP",
            "CONCAT", "CONCAT",
            "IFNULL", "NVL"
        );
        config.types = Map.of(
            "TEXT", "CLOB",
            "VARCHAR2", "VARCHAR",
            "INT", "INTEGER",
            "BIGINT", "BIGINT",
            "DATETIME", "TIMESTAMP"
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY"
        );
        return config;
    }

    private DialectConfig createShenTongConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.of(
            "NOW", "CURRENT_TIMESTAMP",
            "CONCAT", "CONCAT",
            "IFNULL", "COALESCE"
        );
        config.types = Map.of(
            "TEXT", "CLOB",
            "VARCHAR2", "VARCHAR",
            "INT", "INTEGER",
            "BIGINT", "BIGINT",
            "DATETIME", "TIMESTAMP"
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY"
        );
        return config;
    }

    public Set<String> getSupportedDatabases() {
        return dialectConfigs.keySet();
    }

    /**
     * GoldenDB（中兴金篆信通）是 MySQL 兼容的分布式数据库，函数、类型、LIMIT 都沿用 MySQL 写法，
     * 因此这份配置与 TiDB / OceanBase 同形——保留原样即为正确。
     */
    private DialectConfig createGoldenDbConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "NOW"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "IFNULL"),
            Map.entry("DATE_FORMAT", "DATE_FORMAT"),
            Map.entry("SUBSTRING", "SUBSTRING"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "TEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),   // 从 Oracle 系迁过来的 DDL 里会有
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "MEDIUMINT"),
            Map.entry("LONGTEXT", "LONGTEXT"),
            Map.entry("MEDIUMTEXT", "MEDIUMTEXT"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "LIMIT {n}",
            "auto_increment", "AUTO_INCREMENT",
            "comment", "-- {comment}"
        );
        return config;
    }

    /**
     * SQL Server（T-SQL）：NOW -> CURRENT_TIMESTAMP（SQL Server 支持不带括号的 ANSI 写法）、
     * IFNULL -> ISNULL、LENGTH -> LEN、CEIL -> CEILING；TEXT 系列 -> VARCHAR(MAX)、
     * AUTO_INCREMENT -> IDENTITY(1,1)，LIMIT 改写见 {@link #convertSqlServerLimit}。
     * <p>DATE_FORMAT 在 SQL Server 无等价原生函数（只能 CONVERT/FORMAT），格式串翻译风险高，
     * 刻意不映射——保留原样交由 AI 润色或人工处理，不做半对半错的改写。
     */
    private DialectConfig createSqlServerConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "ISNULL"),
            Map.entry("SUBSTRING", "SUBSTRING"),
            Map.entry("LENGTH", "LEN"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEILING"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "VARCHAR(MAX)"),
            Map.entry("MEDIUMTEXT", "VARCHAR(MAX)"),
            Map.entry("LONGTEXT", "VARCHAR(MAX)"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INTEGER", "INT"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "INT"),
            Map.entry("DOUBLE", "FLOAT"),
            Map.entry("FLOAT", "REAL")
        );
        config.syntax = Map.of(
            "auto_increment", "IDENTITY(1,1)",
            "comment", "-- {comment}"
        );
        config.sqlServerTop = true;
        return config;
    }

    /**
     * 目标为 MySQL：这是唯一一个反方向的方言配置——把达梦/Oracle 系的写法收敛回 MySQL，
     * 用于国产库回迁或者两边并行维护的场景。
     * <p>映射键因此是 Oracle 系的函数名（NVL、TO_CHAR、SUBSTR），
     * 同时保留 MySQL 自身写法的恒等项，这样源库本来就是 MySQL 时不会被改坏。
     */
    private DialectConfig createMysqlConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NVL", "IFNULL"),
            Map.entry("TO_CHAR", "DATE_FORMAT"),   // 格式串由 convertToCharPatterns 一并翻译
            Map.entry("SUBSTR", "SUBSTRING"),
            Map.entry("NOW", "NOW"),
            Map.entry("IFNULL", "IFNULL"),
            Map.entry("DATE_FORMAT", "DATE_FORMAT"),
            Map.entry("SUBSTRING", "SUBSTRING"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        // Oracle 系的 SYSDATE 不带括号，MySQL 必须写 NOW()
        config.bareKeywords = Map.of("SYSDATE", "NOW()");
        config.types = Map.ofEntries(
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("NVARCHAR2", "VARCHAR"),
            Map.entry("CLOB", "LONGTEXT"),
            Map.entry("NCLOB", "LONGTEXT"),
            Map.entry("BLOB", "LONGBLOB"),
            Map.entry("NUMBER", "DECIMAL"),
            Map.entry("NUMERIC", "DECIMAL"),
            Map.entry("INTEGER", "INT"),
            Map.entry("TIMESTAMP", "DATETIME"),
            Map.entry("DOUBLE PRECISION", "DOUBLE"),
            Map.entry("REAL", "FLOAT"),
            // MySQL 自身写法保持恒等，避免源库本来就是 MySQL 时被改坏
            Map.entry("VARCHAR", "VARCHAR"),
            Map.entry("TEXT", "TEXT"),
            Map.entry("DATE", "DATE"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL")
        );
        config.syntax = Map.of(
            "limit", "LIMIT {n}",
            "auto_increment", "AUTO_INCREMENT",
            "comment", "-- {comment}"
        );
        config.reverseToMysql = true;
        return config;
    }

    @lombok.Data
    static class DialectConfig {
        Map<String, String> functions = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> syntax = new HashMap<>();

        /** 裸关键字 -> 目标写法，如 Oracle 系不带括号的 SYSDATE -> MySQL 的 NOW()。 */
        Map<String, String> bareKeywords = new HashMap<>();

        /**
         * 本方言是「回迁到 MySQL」方向：需要把 ROWNUM / FETCH FIRST / IDENTITY 这些
         * 国产库写法收敛回 MySQL，而不是像其他方言那样从 MySQL 发散出去。
         */
        boolean reverseToMysql = false;

        /**
         * SQL Server：LIMIT 要改写成 SELECT TOP n（无 OFFSET）或
         * OFFSET m ROWS FETCH NEXT n ROWS ONLY（有 OFFSET），需要结构性改写而非字符串模板。
         */
        boolean sqlServerTop = false;

        /** 所有源类型名合成的单个正则，惰性构建后复用（原来每种类型各编译一次并各扫一趟）。 */
        private volatile Pattern typePattern;

        Pattern typePattern() {
            Pattern cached = typePattern;
            if (cached != null) return cached;
            synchronized (this) {
                if (typePattern == null) {
                    // 长的排前面：保证 LONGTEXT 不会先被 TEXT 部分命中
                    List<String> names = new ArrayList<>(types.keySet());
                    names.sort(Comparator.comparingInt(String::length).reversed());
                    StringJoiner joiner = new StringJoiner("|", "\\b(?:", ")\\b");
                    for (String name : names) {
                        joiner.add(Pattern.quote(name));
                    }
                    typePattern = Pattern.compile(joiner.toString(), Pattern.CASE_INSENSITIVE);
                }
                return typePattern;
            }
        }
    }
}
