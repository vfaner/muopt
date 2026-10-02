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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 在线自更新：下载最新 release 的 jar，覆盖当前运行 jar 并自动重启。
 *
 * <p>仅当以 {@code java -jar xxx.jar} 方式运行时可自动更新；IDEA / {@code mvn spring-boot:run}
 * 运行时代码源是 classes 目录而非 jar，只能下不了场，会抛异常提示手动更新。
 * 重启用平台脚本（sh / bat）：等待旧进程退出后覆盖 jar，再用原启动命令拉起新进程。
 */
@Slf4j
@Service
public class UpdateService {

    private final String currentVersion;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public UpdateService(@Value("${app.version:1.1.0}") String currentVersion) {
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim().replaceFirst("^v", "");
    }

    /** release 里的一条 jar 资产 */
    private record ReleaseAsset(String tag, String name, String downloadUrl) {}

    /** 定位当前进程正在运行的 jar；非 jar 运行返回 null */
    private File locateRunningJar() {
        try {
            File f = new File(UpdateService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return f;
            }
        } catch (Exception e) {
            log.debug("定位运行 jar 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 执行更新：下最新 jar，校验，覆盖当前 jar 并调度自动重启。
     *
     * @param owner         GitHub 仓库 owner（如 vfaner）
     * @param giteeOwner    Gitee 仓库 owner（如 super_rgh；为空时沿用 GitHub owner）
     * @param repo          仓库名（如 muopt）
     * @param targetVersion 最新版本号（用于判断是否需要更新、避免重复更新）
     * @return 说明文本，供前端展示
     */
    public String applyUpdate(String owner, String giteeOwner, String repo, String targetVersion) {
        File jar = locateRunningJar();
        if (jar == null) {
            throw new IllegalStateException("当前不是以可执行 jar 方式运行（如 IDEA / mvn spring-boot:run），"
                    + "无法自动更新，请手动下载最新 jar 后替换重启");
        }

        String target = targetVersion == null ? "" : targetVersion.trim().replaceFirst("^v", "");
        if (!target.isEmpty() && target.equals(currentVersion)) {
            throw new IllegalStateException("已经是最新版本 " + target + "，无需更新");
        }

        String geOwner = giteeOwner == null || giteeOwner.isBlank() ? owner : giteeOwner.trim();
        ReleaseAsset asset = fetchLatestJar(owner, geOwner, repo);
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

        scheduleRestart(jar, tmp);
        return "已下载 " + asset.name() + "（" + asset.tag() + "），应用即将自动重启";
    }

    /** 查最新 release 的可下载 jar 资产：GitHub 优先（assets 信息完整），Gitee 兜底（owner 可能不同账号） */
    private ReleaseAsset fetchLatestJar(String owner, String giteeOwner, String repo) {
        // GitHub：优先精确匹配 仓库名.jar（当前发布约定），回退任意 .jar（兼容历史带版本号的资产）
        try {
            JsonNode gh = getJson("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest");
            String tag = gh.path("tag_name").asText("");
            ReleaseAsset fallback = null;
            for (JsonNode a : gh.path("assets")) {
                String name = a.path("name").asText("");
                String url = a.path("browser_download_url").asText("");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar") || url.isBlank()) {
                    continue;
                }
                ReleaseAsset hit = new ReleaseAsset(tag, name, url);
                if (name.equalsIgnoreCase(repo + ".jar")) {
                    return hit;
                }
                if (fallback == null) {
                    fallback = hit;
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (Exception e) {
            log.debug("GitHub release 查询失败: {}", e.getMessage());
        }
        // Gitee：详情接口不返回附件列表，按约定下载链接构造（owner 用 Gitee 侧账号）
        try {
            JsonNode ge = getJson("https://gitee.com/api/v5/repos/" + giteeOwner + "/" + repo + "/releases/latest");
            String tag = ge.path("tag_name").asText("");
            if (!tag.isEmpty()) {
                // 附件名约定与 GitHub 侧一致：仓库名.jar（不带版本号，自更新就地覆盖同名文件）
                String name = repo + ".jar";
                String url = "https://gitee.com/" + giteeOwner + "/" + repo + "/releases/download/"
                        + tag + "/" + name;
                return new ReleaseAsset(tag, name, url);
            }
        } catch (Exception e) {
            log.debug("Gitee release 查询失败: {}", e.getMessage());
        }
        throw new IllegalStateException("未能获取到最新 release 的可下载 jar（release 需附带 .jar 附件）");
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
        java.nio.file.Files.writeString(f.toPath(), script);
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
        java.nio.file.Files.writeString(f.toPath(), script);
        return f;
    }
}