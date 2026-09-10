package com.sqloptimizer.controller;

import com.sqloptimizer.common.OptimizeResult;
import com.sqloptimizer.common.Result;
import com.sqloptimizer.service.AiService;
import com.sqloptimizer.service.DataSourceService;
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

    @Autowired
    public OptimizeController(SqlOptimizerService optimizerService,
                              DataSourceService dataSourceService,
                              AiService aiService) {
        this.optimizerService = optimizerService;
        this.dataSourceService = dataSourceService;
        this.aiService = aiService;
    }

    /**
     * 优化单条 SQL（手工处理）
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
    public static class StatusResponse {
        private boolean dataSourceConnected;
        private boolean aiConfigured;
        private String dataSourceInfo;
        /** 当前启用的 AI 模型展示名 */
        private String aiInfo;
    }
}
