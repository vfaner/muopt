package com.sqloptimizer.service;

import com.sqloptimizer.common.OptimizeResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
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
 * 单条 SQL 优化（手工优化页）的后台任务管理器。
 *
 * <p>与扫描任务同理：AI 改写可能要等几十秒，同步请求会在用户切菜单/刷新时
 * 被浏览器中断，页面只剩一直转圈的按钮。改为「提交任务 → 轮询状态」后，
 * 任务在服务端执行，前端凭暂存的 jobId 回来继续轮询即可拿到结果。
 */
@Slf4j
@Component
public class OptimizeJobManager {

    /** 已完成任务结果的保留时长（30 分钟） */
    private static final long RETAIN_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static final int MAX_JOBS = 30;

    private final ExecutorService jobPool;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private final SqlOptimizerService optimizerService;

    @Autowired
    public OptimizeJobManager(SqlOptimizerService optimizerService) {
        this.optimizerService = optimizerService;
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "optimize-job-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.jobPool = Executors.newFixedThreadPool(2, tf);
    }

    /** 提交单条优化任务，立即返回 jobId */
    public String start(String sql, boolean enableAi) {
        pruneFinished();
        String jobId = UUID.randomUUID().toString().replace("-", "");
        Job job = new Job(jobId, sql, enableAi);
        jobs.put(jobId, job);
        Future<?> future = jobPool.submit(() -> run(job));
        job.setFuture(future);
        log.info("优化任务已提交: {} AI={}", jobId, enableAi);
        return jobId;
    }

    public Job get(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    /** 取消任务：置取消标志并中断 AI 等待 */
    public boolean cancel(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return false;
        }
        job.cancelRequested = true;
        Future<?> future = job.getFuture();
        if (future != null) {
            future.cancel(true);
        }
        job.markCancelledIfRunning("已取消优化");
        return true;
    }

    private void run(Job job) {
        try {
            OptimizeResult result = optimizerService.optimize(job.getSql(), job.isEnableAi());
            // AI 请求被中断时底层会返回失败结果而非抛异常，这里以取消标志为准
            if (job.isCancelRequested() || Thread.currentThread().isInterrupted()) {
                job.markCancelled("已取消优化");
            } else {
                job.markSuccess(result);
            }
        } catch (Exception e) {
            if (job.isCancelRequested() || isCancellation(e)) {
                job.markCancelled("已取消优化");
            } else {
                log.warn("优化任务 {} 失败: {}", job.getJobId(), e.getMessage());
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

    /** 一个单条优化任务的状态与结果 */
    @Getter
    public static class Job {
        static final String STATUS_RUNNING = "RUNNING";
        static final String STATUS_SUCCESS = "SUCCESS";
        static final String STATUS_FAILED = "FAILED";
        static final String STATUS_CANCELLED = "CANCELLED";

        private final String jobId;
        private final String sql;
        private final boolean enableAi;
        private final long startedAt;

        private volatile String status = STATUS_RUNNING;
        private volatile String message;
        private volatile long finishedAt;
        private volatile OptimizeResult result;
        private volatile Future<?> future;
        private volatile boolean cancelRequested;

        Job(String jobId, String sql, boolean enableAi) {
            this.jobId = jobId;
            this.sql = sql;
            this.enableAi = enableAi;
            this.startedAt = System.currentTimeMillis();
        }

        void setFuture(Future<?> future) {
            this.future = future;
        }

        synchronized void markSuccess(OptimizeResult result) {
            if (!STATUS_RUNNING.equals(status)) {
                return;
            }
            this.result = result;
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
