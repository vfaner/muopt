package com.qqmu.muopt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在线自更新：下载最新 release 的 jar，校验通过后覆盖当前运行 jar 并自动重启。
 *
 * <p>仅当以 {@code java -jar xxx.jar} 方式运行时可自动更新；IDEA / {@code mvn spring-boot:run}
 * 运行时代码源是 classes 目录而非 jar，只能下不了场，会抛异常提示手动更新。
 * 重启用平台脚本（sh / bat）：等待旧进程退出后覆盖 jar，再用原启动命令拉起新进程。
 *
 * <p>安全约束（H2 加固）：
 * <ul>
 *   <li>仓库坐标在服务端写死，不接受客户端传入的 owner/repo，防止指向攻击者仓库；</li>
 *   <li>下载后必须用 release 附带的 {@code .sha256} 校验哈希，防 release 资产被投毒；</li>
 *   <li>仅当 Host 为回环地址时允许执行（见 {@link #isLocalhostHost}），配合调用方检查，
 *       阻断 DNS rebinding 一类的跨域冒用。</li>
 * </ul>
 */
@Slf4j
@Service
public class UpdateService {

    /** 发布仓库坐标：服务端固定，禁止从请求参数获取（GitHub / Gitee 是不同账号）。 */
    public static final String GITHUB_OWNER = "vfaner";
    public static final String GITEE_OWNER = "super_rgh";
    public static final String REPO = "muopt";

    private static final Pattern SHA256_HEX = Pattern.compile("([0-9a-fA-F]{64})");

    /** fat jar codeSource 中的外层 jar 路径：nested:/p/x.jar/… 或 file:/p/x.jar(!…) */
    private static final Pattern OUTER_JAR_PATH = Pattern.compile(
            "(?:nested|file):(/[^!\\s]+?\\.jar)(?:[!/]|$)", Pattern.CASE_INSENSITIVE);

    private final String currentVersion;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public UpdateService(@Value("${app.version:1.1.0}") String currentVersion) {
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim().replaceFirst("^v", "");
    }

    /** release 里的一条 jar 资产及其哈希校验地址 */
    private record ReleaseAsset(String tag, String name, String downloadUrl, String shaUrl) {}

    /**
     * 定位当前进程正在运行的 jar；非 jar 运行返回 null。
     *
     * <p>不能直接 {@code new File(codeSource.getLocation().toURI())}：Spring Boot 3.2 fat jar
     * 里本类的 codeSource 是 {@code jar:nested:/path/muopt.jar/!BOOT-INF/classes!/}，
     * 旧实现必然失败。依次尝试：直接文件 → 位置串里解析外层 jar → 进程命令行 -jar 参数。
     */
    File locateRunningJar() {
        java.net.URL loc = UpdateService.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            File f = new File(loc.toURI());
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return f;
            }
        } catch (Exception e) {
            // 落到字符串解析（nested / jar:file URL 不是普通文件 URI）
        }
        String path = extractJarPath(loc == null ? null : loc.toString());
        if (path != null) {
            File f = new File(path);
            if (f.isFile()) {
                return f;
            }
        }
        String[] args = ProcessHandle.current().info().arguments().orElse(null);
        String argPath = jarFromArgs(args == null ? null : Arrays.asList(args));
        if (argPath != null) {
            File f = new File(argPath);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /**
     * 从 codeSource 位置串中提取外层可执行 jar 的绝对路径。兼容：
     * Boot 3.2 的 {@code jar:nested:/p/muopt.jar/!...}、传统
     * {@code jar:file:/p/muopt.jar!...} 与普通 {@code file:/p/muopt.jar}；
     * 不含 .jar（如 IDEA 的 classes 目录）返回 null。
     */
    static String extractJarPath(String location) {
        if (location == null) {
            return null;
        }
        Matcher m = OUTER_JAR_PATH.matcher(location);
        return m.find() ? m.group(1) : null;
    }

    /** 从进程参数中取 -jar 之后的路径；没有 -jar 返回 null */
    static String jarFromArgs(List<String> args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.size() - 1; i++) {
            if ("-jar".equals(args.get(i))) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    /**
     * 执行更新：下最新 jar，校验哈希，通过后覆盖当前 jar 并调度自动重启。
     *
     * @param targetVersion 最新版本号（用于判断是否需要更新、避免重复更新）
     * @return 说明文本，供前端展示
     */
    public String applyUpdate(String targetVersion) {
        File jar = locateRunningJar();
        if (jar == null) {
            throw new IllegalStateException("当前不是以可执行 jar 方式运行（如 IDEA / mvn spring-boot:run），"
                    + "无法自动更新，请手动下载最新 jar 后替换重启");
        }

        String target = targetVersion == null ? "" : targetVersion.trim().replaceFirst("^v", "");
        if (!target.isEmpty() && target.equals(currentVersion)) {
            throw new IllegalStateException("已经是最新版本 " + target + "，无需更新");
        }

        ReleaseAsset asset = fetchLatestJar();
        String assetVersion = asset.tag() == null ? "" : asset.tag().trim().replaceFirst("^v", "");
        if (!target.isEmpty() && !assetVersion.isEmpty() && !assetVersion.equals(target)) {
            throw new IllegalStateException("release 资产版本（" + asset.tag() + "）与目标版本（" + targetVersion + "）不一致，请稍后重试");
        }

        File tmp = new File(jar.getParentFile(), jar.getName() + ".update");
        download(asset.downloadUrl(), tmp);

        // 有效 fat jar 至少数 MB；过小多半是错误页 / 文本，直接放弃避免覆盖坏文件
        if (!tmp.isFile() || tmp.length() < 1024 * 1024) {
            tmp.delete();
            throw new IllegalStateException("下载到的文件异常（" + (tmp.isFile() ? tmp.length() + " 字节" : "不存在")
                    + "），可能是错误页面而非 jar，已放弃更新");
        }

        // 哈希校验不通过会删除 tmp 并抛异常：没有校验通过的文件绝不允许进入覆盖/重启环节
        verifyDownload(tmp, asset.shaUrl());

        scheduleRestart(jar, tmp);
        return "已下载 " + asset.name() + "（" + asset.tag() + "），应用即将自动重启";
    }

    /** 查最新 release 的可下载 jar 资产：GitHub 优先（assets 信息完整），Gitee 兜底（owner 不同账号） */
    private ReleaseAsset fetchLatestJar() {
        // GitHub：优先精确匹配 仓库名.jar（当前发布约定），回退任意 .jar（兼容历史带版本号的资产）
        try {
            JsonNode gh = getJson("https://api.github.com/repos/" + GITHUB_OWNER + "/" + REPO + "/releases/latest");
            String tag = gh.path("tag_name").asText("");
            ReleaseAsset fallback = null;
            String jarUrl = null;
            String jarName = null;
            String shaUrl = null;
            String fallbackSha = null;
            for (JsonNode a : gh.path("assets")) {
                String name = a.path("name").asText("");
                String url = a.path("browser_download_url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                if (name.toLowerCase(Locale.ROOT).endsWith(".sha256")) {
                    if (name.equalsIgnoreCase(REPO + ".jar.sha256")) {
                        shaUrl = url;
                    } else if (fallbackSha == null) {
                        fallbackSha = url;
                    }
                    continue;
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                if (name.equalsIgnoreCase(REPO + ".jar")) {
                    jarName = name;
                    jarUrl = url;
                } else if (fallback == null) {
                    fallback = new ReleaseAsset(tag, name, url, null);
                }
            }
            if (jarUrl != null) {
                if (shaUrl == null) {
                    shaUrl = fallbackSha;
                }
                if (shaUrl == null) {
                    throw new IllegalStateException("最新 release 缺少 " + REPO + ".jar.sha256 校验文件，无法安全更新");
                }
                return new ReleaseAsset(tag, jarName, jarUrl, shaUrl);
            }
            if (fallback != null && fallbackSha != null) {
                return new ReleaseAsset(tag, fallback.name(), fallback.downloadUrl(), fallbackSha);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("GitHub release 查询失败: {}", e.getMessage());
        }
        // Gitee：详情接口不返回附件列表，按约定下载链接构造（owner 用 Gitee 侧账号）
        try {
            JsonNode ge = getJson("https://gitee.com/api/v5/repos/" + GITEE_OWNER + "/" + REPO + "/releases/latest");
            String tag = ge.path("tag_name").asText("");
            if (!tag.isEmpty()) {
                // 附件名约定与 GitHub 侧一致：仓库名.jar / 仓库名.jar.sha256（不带版本号，自更新就地覆盖同名文件）
                String url = "https://gitee.com/" + GITEE_OWNER + "/" + REPO + "/releases/download/"
                        + tag + "/" + REPO + ".jar";
                String shaUrl = "https://gitee.com/" + GITEE_OWNER + "/" + REPO + "/releases/download/"
                        + tag + "/" + REPO + ".jar.sha256";
                return new ReleaseAsset(tag, REPO + ".jar", url, shaUrl);
            }
        } catch (Exception e) {
            log.debug("Gitee release 查询失败: {}", e.getMessage());
        }
        throw new IllegalStateException("未能获取到最新 release 的可下载 jar（release 需附带 .jar 与 .sha256 附件）");
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "muopt-updater")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }

    private void download(String url, File dest) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "muopt-updater")
                .GET().build();
        try {
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest.toPath()));
            if (resp.statusCode() != 200) {
                dest.delete();
                throw new IllegalStateException("下载失败，HTTP " + resp.statusCode());
            }
        } catch (IOException e) {
            dest.delete();
            throw new IllegalStateException("下载失败: " + e.getMessage());
        } catch (InterruptedException e) {
            dest.delete();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载被中断");
        }
    }

    /**
     * 下载 {@code .sha256} 校验文件并核对已下载 jar 的哈希。
     * 任何失败（校验文件缺失、内容非法、哈希不匹配）都删除下载文件并抛异常，
     * 防止未通过校验的 jar 被重启脚本覆盖执行。
     */
    void verifyDownload(File downloaded, String shaUrl) {
        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(shaUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "muopt-updater")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("校验文件下载失败，HTTP " + resp.statusCode());
            }
            body = resp.body();
        } catch (IllegalStateException e) {
            downloaded.delete();
            throw e;
        } catch (IOException e) {
            downloaded.delete();
            throw new IllegalStateException("校验文件下载失败: " + e.getMessage());
        } catch (InterruptedException e) {
            downloaded.delete();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("校验被中断");
        }

        String expected = parseSha256(body);
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(downloaded.toPath())));
        } catch (Exception e) {
            downloaded.delete();
            throw new IllegalStateException("计算下载文件哈希失败: " + e.getMessage());
        }
        if (!actual.equalsIgnoreCase(expected)) {
            downloaded.delete();
            throw new IllegalStateException(
                    "下载文件的 SHA-256 与校验文件不符（期望 " + expected + "，实际 " + actual + "），已放弃更新");
        }
    }

    /** 从 .sha256 文件内容中提取 64 位十六进制哈希：兼容纯哈希与 sha256sum 的「哈希 文件名」格式。 */
    static String parseSha256(String content) {
        if (content == null) {
            throw new IllegalArgumentException("校验文件为空");
        }
        Matcher m = SHA256_HEX.matcher(content);
        if (!m.find()) {
            throw new IllegalArgumentException("校验文件中未找到合法的 SHA-256 哈希");
        }
        return m.group(1).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断 Host 头是否回环地址（可带端口）：127.0.0.1 / localhost / [::1]。
     * 拒绝任何非回环及形似拼接（127.0.0.1.evil.com、localhost.evil.com），用于阻断
     * DNS rebinding：攻击者域名解析到 127.0.0.1 后，浏览器以攻击者 Host 访问本服务。
     */
    public static boolean isLocalhostHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 && h.substring(1, end).equals("::1")
                    && (h.length() == end + 1 || h.charAt(end + 1) == ':');
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        return h.equals("127.0.0.1") || h.equals("localhost");
    }

    /** 写平台重启脚本并 detached 启动；随后当前进程退出，脚本接管覆盖 + 重启 */
    private void scheduleRestart(File jar, File newJar) {
        String cmdLine = ProcessHandle.current().info().commandLine()
                .orElse("java -jar \"" + jar.getAbsolutePath() + "\"");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        try {
            File script = win ? writeWindowsScript(jar, newJar, cmdLine)
                              : writeUnixScript(jar, newJar, cmdLine);
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd", "/c", "\"" + script.getAbsolutePath() + "\"")
                    : new ProcessBuilder("/bin/sh", script.getAbsolutePath());
            pb.redirectOutput(new File(jar.getParentFile(), "update-restart.log"));
            pb.redirectErrorStream(true);
            pb.start();

            // 让当前 HTTP 响应先返回，再退出进程，脚本的 sleep 之后接管替换重启
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                log.info("自动更新已完成下载，进程退出，等待重启脚本接管");
                System.exit(0);
            }, 2, TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new IllegalStateException("重启脚本启动失败: " + e.getMessage());
        }
    }

    private File writeUnixScript(File jar, File newJar, String cmdLine) throws IOException {
        // cmdLine 来自 ProcessHandle（自己进程的完整启动命令），其引号已正确，
        // 直接内联即可；路径额外用双引号包裹以防空格
        String script = "#!/bin/sh\n"
                + "sleep 4\n"
                + "mv -f \"" + newJar.getAbsolutePath() + "\" \"" + jar.getAbsolutePath() + "\"\n"
                + "cd \"" + jar.getParentFile().getAbsolutePath() + "\"\n"
                + cmdLine + " &\n";
        File f = new File(jar.getParentFile(), "update-restart.sh");
        Files.writeString(f.toPath(), script);
        f.setExecutable(true);
        return f;
    }

    private File writeWindowsScript(File jar, File newJar, String cmdLine) throws IOException {
        String script = "@echo off\r\n"
                + "timeout /t 4 /nobreak >nul\r\n"
                + "move /y \"" + newJar.getAbsolutePath() + "\" \"" + jar.getAbsolutePath() + "\" >nul\r\n"
                + "cd /d \"" + jar.getParentFile().getAbsolutePath() + "\"\r\n"
                + "start \"\" cmd /c \"" + cmdLine + "\"\r\n";
        File f = new File(jar.getParentFile(), "update-restart.bat");
        Files.writeString(f.toPath(), script);
        return f;
    }
}
