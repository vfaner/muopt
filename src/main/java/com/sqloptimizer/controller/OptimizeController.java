package com.sqloptimizer.controller;

import com.sqloptimizer.common.OptimizeResult;
import com.sqloptimizer.common.Result;
import com.sqloptimizer.service.AiService;
import com.sqloptimizer.service.DataSourceService;
import com.sqloptimizer.service.OptimizeJobManager;
import com.sqloptimizer.service.SqlOptimizerService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 优化 API（手工 + 自动/批量）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class OptimizeController {

    private final SqlOptimizerService optimizerService;
    private final DataSourceService dataSourceService;
    private final AiService aiService;
    private final OptimizeJobManager jobManager;

    @Autowired
    public OptimizeController(SqlOptimizerService optimizerService,
                              DataSourceService dataSourceService,
                              AiService aiService,
                              OptimizeJobManager jobManager) {
        this.optimizerService = optimizerService;
        this.dataSourceService = dataSourceService;
        this.aiService = aiService;
        this.jobManager = jobManager;
    }

    /**
     * 提交单条 SQL 的异步优化任务，立即返回 jobId。
     * AI 改写可能耗时几十秒，放后台执行可避免用户切菜单/刷新时请求被浏览器中断。
     */
    @PostMapping("/optimize/start")
    public Result<StartResponse> startOptimize(@RequestBody OptimizeRequest request) {
        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            return Result.error(400, "SQL 不能为空");
        }
        String jobId = jobManager.start(request.getSql(), request.isEnableAi());
        return Result.success(new StartResponse(jobId));
    }

    /**
     * 查询优化任务状态；SUCCESS 时携带 OptimizeResult，任务不存在返回 404。
     */
    @GetMapping("/optimize/jobs/{jobId}")
    public Result<JobView> optimizeJobStatus(@PathVariable String jobId) {
        OptimizeJobManager.Job job = jobManager.get(jobId);
        if (job == null) {
            return Result.error(404, "优化任务不存在或已过期（服务可能重启过），请重新优化");
        }
        return Result.success(JobView.of(job));
    }

    /** 取消优化任务（中断 AI 等待） */
    @PostMapping("/optimize/jobs/{jobId}/cancel")
    public Result<?> cancelOptimizeJob(@PathVariable String jobId) {
        if (!jobManager.cancel(jobId)) {
            return Result.error(404, "优化任务不存在或已结束");
        }
        return Result.success(true);
    }

    /**
     * 优化单条 SQL（同步接口，保留兼容；页面已改用 /start + 轮询）
     */
    @PostMapping("/optimize")
    public Result<OptimizeResult> optimize(@RequestBody OptimizeRequest request) {
        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            return Result.error(400, "SQL 不能为空");
        }
        OptimizeResult result = optimizerService.optimize(request.getSql(), request.isEnableAi());
        return Result.success(result);
    }

    /**
     * 批量优化多条 SQL（自动处理）。以分号分隔多条 SQL。
     */
    @PostMapping("/optimize/batch")
    public Result<List<OptimizeResult>> optimizeBatch(@RequestBody OptimizeRequest request) {
        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            return Result.error(400, "SQL 不能为空");
        }
        List<OptimizeResult> results = new ArrayList<>();
        for (String single : splitSql(request.getSql())) {
            if (!single.trim().isEmpty()) {
                try {
                    results.add(optimizerService.optimize(single, request.isEnableAi()));
                } catch (Exception e) {
                    log.warn("批量优化单条失败: {}", e.getMessage());
                    OptimizeResult err = new OptimizeResult();
                    err.setSourceSql(single.trim());
                    err.getTips().add("该条 SQL 优化失败：" + e.getMessage());
                    results.add(err);
                }
            }
        }
        return Result.success(results);
    }

    /**
     * 全局状态：是否已配置数据源 / 是否配置了 AI（供前端页头提示联动）
     */
    @GetMapping("/status")
    public Result<StatusResponse> status() {
        StatusResponse resp = new StatusResponse();
        resp.setDataSourceConnected(dataSourceService.isConnected());
        resp.setAiConfigured(aiService.isConfigured());
        if (dataSourceService.isConnected() && dataSourceService.getCurrentConfig() != null) {
            var c = dataSourceService.getCurrentConfig();
            String target = "custom".equalsIgnoreCase(c.getDbType())
                    ? c.getCustomUrl()
                    : c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
            resp.setDataSourceInfo(c.getDbType() + " @ " + target + "（" + c.getName() + "）");
        }
        if (aiService.isConfigured()) {
            resp.setAiInfo(aiService.getActiveName());
        }
        return Result.success(resp);
    }

    /**
     * 按分号拆分 SQL，忽略字符串字面量中的分号
     */
    private List<String> splitSql(String sqlText) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < sqlText.length(); i++) {
            char c = sqlText.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (c == ';' && !inSingle && !inDouble) {
                list.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().trim().isEmpty()) {
            list.add(cur.toString());
        }
        return list;
    }

    @Data
    public static class OptimizeRequest {
        private String sql;
        private boolean enableAi = false;
    }

    @Data
    @lombok.AllArgsConstructor
    public static class StartResponse {
        private String jobId;
    }

    /** 任务状态视图：成功后才携带结果，运行中轮询响应保持轻量 */
    @Data
    public static class JobView {
        private String jobId;
        /** RUNNING / SUCCESS / FAILED / CANCELLED */
        private String status;
        private String message;
        private long startedAt;
        private long finishedAt;
        private OptimizeResult result;

        static JobView of(OptimizeJobManager.Job job) {
            JobView v = new JobView();
            v.jobId = job.getJobId();
            v.status = job.getStatus();
            v.message = job.getMessage();
            v.startedAt = job.getStartedAt();
            v.finishedAt = job.getFinishedAt();
            if ("SUCCESS".equals(job.getStatus())) {
                v.result = job.getResult();
            }
            return v;
        }
    }

    @Data
    public static class StatusResponse {
        private boolean dataSourceConnected;
        private boolean aiConfigured;
        private String dataSourceInfo;
        /** 当前启用的 AI 模型展示名 */
        private String aiInfo;
    }
}
