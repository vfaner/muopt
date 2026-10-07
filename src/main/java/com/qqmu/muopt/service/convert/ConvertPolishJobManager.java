package com.qqmu.muopt.service.convert;

import com.qqmu.muopt.service.AiService;
import com.qqmu.muopt.service.job.AbstractJobManager;
import com.qqmu.muopt.service.job.CancelToken;
import org.springframework.stereotype.Component;

/**
 * 手工转换「AI 方言润色」的后台任务管理器。
 *
 * <p>规则转换毫秒级完成，但 AI 润色要调用大模型、可能等几十秒；同步请求在用户切菜单/刷新时
 * 会被浏览器中断，页面只剩一直转圈的按钮。改为「提交任务 → 轮询状态」后，任务在服务端执行，
 * 前端凭暂存的 jobId 回来继续轮询即可拿到结果。
 *
 * <p>通用机制见 {@link AbstractJobManager}，本类只定义参数类型与执行逻辑（规则转换 + AI 润色）。
 */
@Component
public class ConvertPolishJobManager extends AbstractJobManager<ConvertPolishJobManager.Task, String> {

    /** 润色任务参数 */
    public record Task(String sourceSql, String targetDb) {
    }

    private final SqlConverter sqlConverter;
    private final AiService aiService;

    public ConvertPolishJobManager(SqlConverter sqlConverter, AiService aiService) {
        this.sqlConverter = sqlConverter;
        this.aiService = aiService;
    }

    @Override
    protected String execute(Task task, CancelToken token) {
        String convertedSql = sqlConverter.convert(task.sourceSql(), task.targetDb());
        return aiService.polishConvertedSql(convertedSql, task.targetDb());
    }

    @Override
    protected String threadPrefix() {
        return "convert-polish-job-";
    }

    @Override
    protected String cancelMessage() {
        return "已取消润色";
    }
}
