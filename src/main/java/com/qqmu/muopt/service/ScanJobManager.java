package com.qqmu.muopt.service;

import com.qqmu.muopt.common.ScanItem;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 扫描任务管理器：把扫描放到服务端后台线程执行。
 *
 * <p>页面是多 HTML 整页跳转结构，同步请求会在用户切菜单/刷新时被浏览器中断，
 * 结果也无法带回。改为「提交任务 → 轮询状态」后：
 * <ul>
 *   <li>任务生命周期完全在服务端，与 HTTP 连接无关，切页面/刷新不影响扫描；</li>
 *   <li>前端把 jobId 存在 sessionStorage，回来后凭 jobId 继续轮询并展示结果；</li>
 *   <li>任务结果在内存中保留 {@link #RETAIN_MILLIS}（服务重启后任务丢失，前端按 404 引导重扫）。</li>
 * </ul>
 */
@Slf4j
@Component
public class ScanJobManager {

    /** 已完成任务结果在内存中的保留时长（30 分钟） */
    private static final long RETAIN_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /** 内存中同时保留的任务上限（超出时优先淘汰最早的已结束任务） */
    private static final int MAX_JOBS = 20;

    /** 同时执行的扫描任务数；第 3 个任务排队（排队中的任务可被取消） */
    private final ExecutorService jobPool;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private final ProjectScanService scanService;

    @Autowired
    public ScanJobManager(ProjectScanService scanService) {
        this.scanService = scanService;
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "scan-job-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.jobPool = Executors.newFixedThreadPool(2, tf);
    }

    /** 提交一个扫描任务，立即返回 jobId */
    public String start(String projectPath, boolean enableAi) {
        pruneFinished();
        String jobId = UUID.randomUUID().toString().replace("-", "");
        Job job = new Job(jobId, projectPath, enableAi);
        jobs.put(jobId, job);
        Future<?> future = jobPool.submit(() -> run(job));
        job.setFuture(future);
        log.info("扫描任务已提交: {} 路径={} AI={}", jobId, projectPath, enableAi);
        return jobId;
    }

    /** 查询任务快照；不存在返回 null（已过期或服务重启过） */
    public Job get(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    /**
     * 取消任务。排队中/运行中都尽力中断：AI 等待会抛 InterruptedException，
     * 本地分析很快，中断主要作用在 AI 阶段。
     *
     * @return 任务是否存在
     */
    public boolean cancel(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return false;
        }
        Future<?> future = job.getFuture();
        if (future != null) {
            future.cancel(true);
        }
        // 任务可能正好在收尾，cancel 没拦住时由 run() 按实际结果落状态，这里做兜底
        job.markCancelledIfRunning("用户取消了扫描");
        return true;
    }

    private void run(Job job) {
        try {
            List<ScanItem> items = scanService.scan(job.getProjectPath(), job.isEnableAi());
            if (Thread.currentThread().isInterrupted()) {
                job.markCancelled("扫描已被取消");
            } else {
                job.markSuccess(items);
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || isCancellation(e)) {
                job.markCancelled("扫描已被取消");
            } else {
                log.warn("扫描任务 {} 失败: {}", job.getJobId(), e.getMessage());
                job.markFailed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    private boolean isCancellation(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof InterruptedException
                    || c instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    /** 清理过期任务，并在超上限时淘汰最早结束的任务 */
    private void pruneFinished() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(e -> {
            Job j = e.getValue();
            return !Job.STATUS_RUNNING.equals(j.getStatus())
                    && j.getFinishedAt() > 0
                    && now - j.getFinishedAt() > RETAIN_MILLIS;
        });
        if (jobs.size() < MAX_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(j -> !Job.STATUS_RUNNING.equals(j.getStatus()))
                .sorted(Comparator.comparingLong(Job::getFinishedAt))
                .limit(jobs.size() - MAX_JOBS + 1L)
                .forEach(j -> jobs.remove(j.getJobId()));
    }

    /**
     * 一个扫描任务的状态与结果。轮询时运行中的任务不携带结果，
     * 只有 SUCCESS 才把 items 返回给前端，避免轮询响应体过大。
     */
    @Getter
    public static class Job {
        static final String STATUS_RUNNING = "RUNNING";
        static final String STATUS_SUCCESS = "SUCCESS";
        static final String STATUS_FAILED = "FAILED";
        static final String STATUS_CANCELLED = "CANCELLED";

        private final String jobId;
        private final String projectPath;
        private final boolean enableAi;
        private final long startedAt;

        private volatile String status = STATUS_RUNNING;
        private volatile String message;
        private volatile long finishedAt;
        private volatile Integer itemCount;
        private volatile List<ScanItem> result;
        private volatile Future<?> future;

        Job(String jobId, String projectPath, boolean enableAi) {
            this.jobId = jobId;
            this.projectPath = projectPath;
            this.enableAi = enableAi;
            this.startedAt = System.currentTimeMillis();
        }

        void setFuture(Future<?> future) {
            this.future = future;
        }

        synchronized void markSuccess(List<ScanItem> items) {
            if (!STATUS_RUNNING.equals(status)) {
                return;
            }
            this.result = items == null ? new ArrayList<>() : items;
            this.itemCount = this.result.size();
            finish(STATUS_SUCCESS, null);
        }

        synchronized void markFailed(String message) {
            if (!STATUS_RUNNING.equals(status)) {
                return;
            }
            finish(STATUS_FAILED, message);
        }

        synchronized void markCancelled(String message) {
            if (!STATUS_RUNNING.equals(status)) {
                return;
            }
            finish(STATUS_CANCELLED, message);
        }

        /** cancel(true) 后任务线程可能正进入收尾，保证状态能翻成 CANCELLED */
        synchronized void markCancelledIfRunning(String message) {
            markCancelled(message);
        }

        private void finish(String status, String message) {
            this.status = status;
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
        }
    }
}
