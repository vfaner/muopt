package com.qqmu.muopt.service;

import com.qqmu.muopt.common.OptimizeResult;
import com.qqmu.muopt.service.job.AbstractJobManager;
import com.qqmu.muopt.service.job.CancelToken;
import org.springframework.stereotype.Component;

/**
 * 单条 SQL 优化（手工优化页）的后台任务管理器。
 *
 * <p>AI 改写可能要等几十秒，同步请求会在用户切菜单/刷新时被浏览器中断，
 * 页面只剩一直转圈的按钮。改为「提交任务 → 轮询状态」后，任务在服务端执行，
 * 前端凭暂存的 jobId 回来继续轮询即可拿到结果。
 *
 * <p>通用机制见 {@link AbstractJobManager}，本类只定义参数类型与执行逻辑。
 */
@Component
public class OptimizeJobManager extends AbstractJobManager<OptimizeJobManager.Task, OptimizeResult> {

    /** 优化任务参数 */
    public record Task(String sql, boolean enableAi) {
    }

    private final SqlOptimizerService optimizerService;

    public OptimizeJobManager(SqlOptimizerService optimizerService) {
        this.optimizerService = optimizerService;
    }

    @Override
    protected OptimizeResult execute(Task task, CancelToken token) {
        return optimizerService.optimize(task.sql(), task.enableAi());
    }

    @Override
    protected String threadPrefix() {
        return "optimize-job-";
    }

    @Override
    protected String cancelMessage() {
        return "已取消优化";
    }
}
