package com.qqmu.muopt.service;

import com.qqmu.muopt.common.ScanItem;
import com.qqmu.muopt.service.job.AbstractJobManager;
import com.qqmu.muopt.service.job.CancelToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 扫描任务管理器：把扫描放到服务端后台线程执行。
 *
 * <p>页面是多 HTML 整页跳转结构，同步请求会在用户切菜单/刷新时被浏览器中断，
 * 结果也无法带回。任务生命周期完全在服务端，与 HTTP 连接无关；前端把 jobId 存在
 * sessionStorage，回来后凭 jobId 继续轮询并展示结果；任务结果在内存中保留 30 分钟
 * （服务重启后任务丢失，前端按 404 引导重扫）。
 *
 * <p>通用机制见 {@link AbstractJobManager}，本类只定义参数类型与执行逻辑。
 */
@Component
public class ScanJobManager extends AbstractJobManager<ScanJobManager.Task, List<ScanItem>> {

    /** 扫描任务参数 */
    public record Task(String projectPath, boolean enableAi) {
    }

    private final ProjectScanService scanService;

    public ScanJobManager(ProjectScanService scanService) {
        this.scanService = scanService;
    }

    @Override
    protected List<ScanItem> execute(Task task, CancelToken token) {
        return scanService.scan(task.projectPath(), task.enableAi());
    }

    @Override
    protected String threadPrefix() {
        return "scan-job-";
    }

    @Override
    protected String cancelMessage() {
        return "扫描已被取消";
    }

    @Override
    protected int maxJobs() {
        return 20;
    }
}
