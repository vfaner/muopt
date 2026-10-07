package com.qqmu.muopt.controller;

import com.qqmu.muopt.common.ConversionItem;
import com.qqmu.muopt.common.ConvertScanTask;
import com.qqmu.muopt.common.Result;
import com.qqmu.muopt.service.AiService;
import com.qqmu.muopt.service.convert.ConvertPolishJobManager;
import com.qqmu.muopt.service.convert.ConvertReplaceService;
import com.qqmu.muopt.service.convert.ConvertScanService;
import com.qqmu.muopt.service.convert.SqlConverter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SQL 信创转换：手工转换（单条）与扫描转换（项目批量改造）的 API。
 *
 * <p>规则转换由 {@link SqlConverter} 完成（毫秒级、确定性）；
 * AI 只负责两件事——转换后的方言润色（{@link AiService#polishConvertedSql}）
 * 与图片 OCR 识别 SQL（{@link AiService#recognizeImage}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/convert")
public class ConvertController {

    private final SqlConverter sqlConverter;
    private final ConvertScanService scanService;
    private final ConvertReplaceService replaceService;
    private final AiService aiService;
    private final ConvertPolishJobManager polishJobManager;

    @Autowired
    public ConvertController(SqlConverter sqlConverter, ConvertScanService scanService,
                             ConvertReplaceService replaceService, AiService aiService,
                             ConvertPolishJobManager polishJobManager) {
        this.sqlConverter = sqlConverter;
        this.scanService = scanService;
        this.replaceService = replaceService;
        this.aiService = aiService;
        this.polishJobManager = polishJobManager;
    }

    // ==================== 手工转换 ====================

    /** 规则转换（不做 AI 润色） */
    @PostMapping
    public Result<ConvertResponse> convert(@RequestBody ConvertRequest request) {
        String err = validateConvert(request);
        if (err != null) {
            return Result.error(400, err);
        }
        try {
            String convertedSql = sqlConverter.convert(request.getSourceSql(), request.getTargetDb());
            return Result.success(new ConvertResponse(request.getSourceSql(), convertedSql,
                    request.getTargetDb(), false));
        } catch (Exception e) {
            log.warn("SQL 转换失败: {}", e.getMessage());
            return Result.error("SQL 转换失败: " + e.getMessage());
        }
    }

    /** 规则转换 + AI 方言润色 */
    @PostMapping("/polish")
    public Result<ConvertResponse> polish(@RequestBody ConvertRequest request) {
        String err = validateConvert(request);
        if (err != null) {
            return Result.error(400, err);
        }
        try {
            String convertedSql = sqlConverter.convert(request.getSourceSql(), request.getTargetDb());
            String polished = aiService.polishConvertedSql(convertedSql, request.getTargetDb());
            return Result.success(new ConvertResponse(request.getSourceSql(), polished,
                    request.getTargetDb(), true));
        } catch (Exception e) {
            log.warn("AI 润色失败: {}", e.getMessage());
            return Result.error("AI 润色失败: " + e.getMessage());
        }
    }

    /**
     * 提交手工转换的 AI 方言润色任务，立即返回 jobId。
     * AI 润色可能耗时几十秒，放后台执行可避免用户切菜单/刷新时请求被浏览器中断。
     */
    @PostMapping("/polish/start")
    public Result<PolishStartResponse> startPolish(@RequestBody ConvertRequest request) {
        String err = validateConvert(request);
        if (err != null) {
            return Result.error(400, err);
        }
        String jobId = polishJobManager.start(
                new ConvertPolishJobManager.Task(request.getSourceSql(), request.getTargetDb()));
        return Result.success(new PolishStartResponse(jobId));
    }

    /** 查询手工转换润色任务状态；SUCCESS 时携带结果，任务不存在返回 404。 */
    @GetMapping("/polish/jobs/{jobId}")
    public Result<PolishJobView> polishJobStatus(@PathVariable String jobId) {
        com.qqmu.muopt.service.job.Job<ConvertPolishJobManager.Task, String> job = polishJobManager.get(jobId);
        if (job == null) {
            return Result.error(404, "润色任务不存在或已过期（服务可能重启过），请重新转换");
        }
        return Result.success(PolishJobView.of(job));
    }

    /** 取消手工转换润色任务（中断 AI 等待） */
    @PostMapping("/polish/jobs/{jobId}/cancel")
    public Result<?> cancelPolishJob(@PathVariable String jobId) {
        if (!polishJobManager.cancel(jobId)) {
            return Result.error(404, "润色任务不存在或已结束");
        }
        return Result.success(true);
    }

    private String validateConvert(ConvertRequest request) {
        if (request.getSourceSql() == null || request.getSourceSql().trim().isEmpty()) {
            return "源SQL不能为空";
        }
        if (request.getTargetDb() == null || request.getTargetDb().trim().isEmpty()) {
            return "目标数据库不能为空";
        }
        return null;
    }

    /**
     * OCR 识别图片中的 SQL（需要启用的模型配置了视觉模型）
     */
    @PostMapping("/ocr")
    public Result<String> ocr(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "上传文件不能为空");
        }
        try {
            byte[] imageData = file.getBytes();
            // 不信任客户端声明的 Content-Type，按实际字节头判定真实图片类型
            String mimeType = detectImageMimeType(imageData);
            if (mimeType == null) {
                return Result.error(400, "只支持图片文件（.png/.jpg/.jpeg/.bmp/.gif/.webp）");
            }
            return Result.success(aiService.recognizeImage(imageData, mimeType));
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", e.getMessage());
            return Result.error("读取上传文件失败: " + e.getMessage());
        } catch (Exception e) {
            log.warn("OCR 识别失败: {}", e.getMessage());
            return Result.error(e.getMessage() == null ? "OCR 识别失败" : e.getMessage());
        }
    }

    /** 根据文件头魔数识别图片类型，无法识别时返回 null */
    static String detectImageMimeType(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if (matches(data, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (matches(data, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (matches(data, 0, 0x42, 0x4D)) {
            return "image/bmp";
        }
        if (matches(data, 0, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        if (matches(data, 0, 0x52, 0x49, 0x46, 0x46) && matches(data, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean matches(byte[] data, int offset, int... signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** 支持的目标数据库（含中文展示名，前端直接渲染下拉框） */
    @GetMapping("/databases")
    public Result<List<DbOption>> getDatabases() {
        List<DbOption> list = new ArrayList<>();
        for (String code : sqlConverter.getSupportedDatabases()) {
            list.add(new DbOption(code, DISPLAY_NAMES.getOrDefault(code, code)));
        }
        list.sort(Comparator.comparing(DbOption::getCode));
        return Result.success(list);
    }

    private static final java.util.Map<String, String> DISPLAY_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("mysql", "MySQL"),
            java.util.Map.entry("mariadb", "MariaDB"),
            java.util.Map.entry("postgresql", "PostgreSQL"),
            java.util.Map.entry("gaussdb", "GaussDB（华为）"),
            java.util.Map.entry("opengauss", "openGauss"),
            java.util.Map.entry("kingbase", "人大金仓 KingbaseES"),
            java.util.Map.entry("dameng", "达梦 DM"),
            java.util.Map.entry("oracle", "Oracle"),
            java.util.Map.entry("yashandb", "崖山 YashanDB"),
            java.util.Map.entry("sqlserver", "SQL Server"),
            java.util.Map.entry("db2", "DB2"),
            java.util.Map.entry("oceanbase", "OceanBase"),
            java.util.Map.entry("tidb", "TiDB"),
            java.util.Map.entry("highgo", "瀚高 HighGo"),
            java.util.Map.entry("vastbase", "海量 Vastbase"),
            java.util.Map.entry("gbase", "GBase（南大通用）"),
            java.util.Map.entry("golden", "GoldenDB（中兴）"),
            java.util.Map.entry("shentong", "神通数据库 Oscar")
    );

    // ==================== 扫描转换 ====================

    /** 启动扫描任务，立即返回 taskId（任务在服务端后台执行） */
    @PostMapping("/scan")
    public Result<ScanResponse> scan(@RequestBody ScanRequest request) {
        if (request.getPath() == null || request.getPath().trim().isEmpty()) {
            return Result.error(400, "项目目录不能为空");
        }
        if (request.getTargetDb() == null || request.getTargetDb().trim().isEmpty()) {
            return Result.error(400, "目标数据库不能为空");
        }
        try {
            String taskId = scanService.startScan(
                    request.getPath().trim(), request.getTargetDb(), request.isEnableAi());
            ScanResponse response = new ScanResponse();
            response.setTaskId(taskId);
            response.setMessage(request.isEnableAi() ? "扫描任务已启动（含 AI 润色）" : "扫描任务已启动");
            return Result.success(response);
        } catch (Exception e) {
            log.warn("启动扫描任务失败: {}", e.getMessage());
            return Result.error("启动扫描任务失败: " + e.getMessage());
        }
    }

    /** 查询扫描进度（前端轮询） */
    @GetMapping("/scan/progress")
    public Result<ProgressResponse> getProgress(@RequestParam("taskId") String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return Result.error(400, "任务ID不能为空");
        }
        ConvertScanTask task = scanService.getConvertScanTask(taskId);
        if (task == null) {
            return Result.error(404, "任务不存在或已过期，请重新扫描");
        }
        ProgressResponse response = new ProgressResponse();
        response.setTaskId(taskId);
        response.setProgress(task.getProgress());
        response.setStatus(task.getStatus());
        response.setItems(task.getItems());
        response.setPath(task.getPath());
        response.setTargetDb(task.getTargetDb());
        response.setEnableAi(task.isEnableAi());
        response.setPhase(task.getPhase());
        response.setAiTotal(task.getAiTotal());
        response.setAiDone(task.getAiDone());
        response.setAiApplied(task.getAiApplied());
        response.setAiFailed(task.getAiFailed());
        response.setAiSkipped(task.getAiSkipped());
        response.setAiMessage(task.getAiMessage());
        return Result.success(response);
    }

    /** 按任务执行批量替换（写回源文件，自动创建 .bak 备份） */
    @PostMapping("/replace")
    public Result<ConvertReplaceService.ReplaceResult> replace(@RequestBody ReplaceRequest request) {
        if (request.getTaskId() == null || request.getTaskId().trim().isEmpty()) {
            return Result.error(400, "任务ID不能为空");
        }
        try {
            return Result.success(replaceService.replaceByTaskId(request.getTaskId()));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("执行替换失败: {}", e.getMessage());
            return Result.error("执行替换失败: " + e.getMessage());
        }
    }

    /** 使用自定义清单执行替换（前端勾选部分条目时） */
    @PostMapping("/replace/custom")
    public Result<ConvertReplaceService.ReplaceResult> replaceCustom(@RequestBody List<ConversionItem> items) {
        if (items == null || items.isEmpty()) {
            return Result.error(400, "替换清单不能为空");
        }
        try {
            return Result.success(replaceService.replaceItems(items));
        } catch (Exception e) {
            log.warn("执行替换失败: {}", e.getMessage());
            return Result.error("执行替换失败: " + e.getMessage());
        }
    }

    /** 列出子目录（前端文件夹选择器）；path 为空时返回用户主目录 */
    @GetMapping("/dirs")
    public Result<DirListing> listDirectories(@RequestParam(value = "path", required = false) String path) {
        File dir = (path == null || path.trim().isEmpty())
                ? new File(System.getProperty("user.home"))
                : new File(path.trim());
        if (!dir.exists()) {
            return Result.error(404, "路径不存在: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            return Result.error(400, "不是目录: " + dir.getAbsolutePath());
        }

        DirListing response = new DirListing();
        response.setCurrentPath(dir.getAbsolutePath());
        response.setParentPath(dir.getParent());

        File[] subDirs = dir.listFiles(File::isDirectory);
        List<DirItem> items = new ArrayList<>();
        if (subDirs != null) {
            Arrays.sort(subDirs, Comparator.comparing(f -> f.getName().toLowerCase()));
            for (File subDir : subDirs) {
                if (subDir.getName().startsWith(".")) {
                    continue;
                }
                DirItem item = new DirItem();
                item.setName(subDir.getName());
                item.setPath(subDir.getAbsolutePath());
                items.add(item);
            }
        }
        response.setDirectories(items);
        return Result.success(response);
    }

    // ==================== DTO ====================

    @Data
    public static class ConvertRequest {
        private String sourceSql;
        private String targetDb;
    }

    @Data
    public static class ConvertResponse {
        private final String sourceSql;
        private final String convertedSql;
        private final String targetDb;
        private final boolean optimized;
    }

    @Data
    @lombok.AllArgsConstructor
    public static class PolishStartResponse {
        private String jobId;
    }

    /** 润色任务状态视图：成功后才携带结果，运行中轮询响应保持轻量 */
    @Data
    public static class PolishJobView {
        private String jobId;
        /** RUNNING / SUCCESS / FAILED / CANCELLED */
        private String status;
        private String message;
        private long startedAt;
        private long finishedAt;
        private ConvertResponse result;

        static PolishJobView of(com.qqmu.muopt.service.job.Job<ConvertPolishJobManager.Task, String> job) {
            PolishJobView v = new PolishJobView();
            v.jobId = job.getJobId();
            v.status = job.getStatus();
            v.message = job.getMessage();
            v.startedAt = job.getStartedAt();
            v.finishedAt = job.getFinishedAt();
            if (job.getStatus().equals("SUCCESS")) {
                v.result = new ConvertResponse(job.getParams().sourceSql(), job.getResult(),
                        job.getParams().targetDb(), true);
            }
            return v;
        }
    }

    @Data
    public static class DbOption {
        private final String code;
        private final String name;
    }

    @Data
    public static class ScanRequest {
        private String path;
        private String targetDb;
        /** 是否在规则转换之后再做一轮 AI 方言润色 */
        private boolean enableAi;
    }

    @Data
    public static class ScanResponse {
        private String taskId;
        private String message;
    }

    @Data
    public static class ProgressResponse {
        private String taskId;
        private int progress;
        private String status;
        private List<ConversionItem> items;
        private String path;
        private String targetDb;
        private boolean enableAi;
        private String phase;
        private int aiTotal;
        private int aiDone;
        private int aiApplied;
        private int aiFailed;
        private int aiSkipped;
        private String aiMessage;
    }

    @Data
    public static class ReplaceRequest {
        private String taskId;
    }

    @Data
    public static class DirItem {
        private String name;
        private String path;
    }

    @Data
    public static class DirListing {
        private String currentPath;
        private String parentPath;
        private List<DirItem> directories;
    }
}
