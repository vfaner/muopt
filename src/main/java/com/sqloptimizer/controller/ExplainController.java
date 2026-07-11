package com.sqloptimizer.controller;

import com.sqloptimizer.common.ExplainResult;
import com.sqloptimizer.common.Result;
import com.sqloptimizer.service.ExplainService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * SQL 执行计划分析 API（需数据源）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ExplainController {

    private final ExplainService explainService;

    @Autowired
    public ExplainController(ExplainService explainService) {
        this.explainService = explainService;
    }

    @PostMapping("/explain")
    public Result<ExplainResult> explain(@RequestBody ExplainRequest request) {
        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            return Result.error(400, "SQL 不能为空");
        }
        ExplainResult result = explainService.explain(request.getSql());
        return Result.success(result);
    }

    @Data
    public static class ExplainRequest {
        private String sql;
    }
}
