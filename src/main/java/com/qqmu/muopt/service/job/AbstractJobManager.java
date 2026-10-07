package com.qqmu.muopt.service.job;

import lombok.extern.slf4j.Slf4j;

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
 * 后台任务管理器基类：统一「提交任务 → 后台执行 → 轮询状态 → 取消 → 过期清理」。
 *
 * <p>原 OptimizeJobManager / ScanJobManager / ConvertPolishJobManager 各有一份近乎相同的
 * 实现（线程池、任务表、状态流转、prune 逻辑全部重复）。本类把不变部分收口，子类只
 * 提供三件事：{@link #execute} 执行逻辑、{@link #threadPrefix} 线程名前缀，以及少量
 * 可覆盖的配置钩子。
 */
@Slf4j
public abstract class AbstractJobManager<P, R> {

    /** 已完成任务结果的保留时长（30 分钟） */
    private static final long RETAIN_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /** 同时执行的任务数；第 3 个任务排队（排队中的任务可被取消） */
    private static final int POOL_SIZE = 2;

    private final ExecutorService jobPool;
    private final Map<String, Job<P, R>> jobs = new ConcurrentHashMap<>();

    protected AbstractJobManager() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, threadPrefix() + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.jobPool = Executors.newFixedThreadPool(POOL_SIZE, tf);
    }

    /** 提交任务，立即返回 jobId */
    public String start(P params) {
        pruneFinished();
        String jobId = UUID.randomUUID().toString().replace("-", "");
        Job<P, R> job = new Job<>(jobId, params);
        jobs.put(jobId, job);
        Future<?> future = jobPool.submit(() -> run(job));
        job.setFuture(future);
        log.info("后台任务已提交: {} ({})", jobId, threadPrefix());
        return jobId;
    }

    public Job<P, R> get(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    /** 取消任务：置取消标志并中断线程（AI 等待会响应中断）。任务不存在返回 false */
    public boolean cancel(String jobId) {
        Job<P, R> job = jobs.get(jobId);
        if (job == null) {
            return false;
        }
        job.cancelToken().requestCancel();
        Future<?> future = job.getFuture();
        if (future != null) {
            future.cancel(true);
        }
        job.markCancelledIfRunning(cancelMessage());
        return true;
    }

    private void run(Job<P, R> job) {
        try {
            R result = execute(job.getParams(), job.cancelToken());
            if (job.cancelToken().isCancelRequested() || Thread.currentThread().isInterrupted()) {
                job.markCancelledIfRunning(cancelMessage());
            } else {
                job.markSuccess(result, resultItemCount(result));
            }
        } catch (Exception e) {
            if (job.cancelToken().isCancelRequested() || isCancellation(e)) {
                job.markCancelledIfRunning(cancelMessage());
            } else {
                log.warn("后台任务 {} 失败: {}", job.getJobId(), e.getMessage());
                job.markFailed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    /** 子类执行逻辑；token 用于感知取消。抛异常时任务落 FAILED（取消类异常落 CANCELLED） */
    protected abstract R execute(P params, CancelToken token) throws Exception;

    /** 工作线程名前缀，如 "scan-job-" */
    protected abstract String threadPrefix();

    /** 取消时记录的提示语，子类可覆盖（不同页面文案不同） */
    protected String cancelMessage() {
        return "任务已取消";
    }

    /** 内存中同时保留的任务上限；超限时优先淘汰最早结束的任务。默认 30 */
    protected int maxJobs() {
        return 30;
    }

    /**
     * 结果条目数钩子：结果为 List 时返回其大小（扫描任务用于前端展示计数），
     * 其它情况返回 null。子类可覆盖。
     */
    protected Integer resultItemCount(R result) {
        return result instanceof java.util.List<?> list ? list.size() : null;
    }

    /** 测试用：直接访问条目数钩子 */
    Integer callItemCount(R result) {
        return resultItemCount(result);
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
            Job<P, R> j = e.getValue();
            return !Job.STATUS_RUNNING.equals(j.getStatus())
                    && j.getFinishedAt() > 0
                    && now - j.getFinishedAt() > RETAIN_MILLIS;
        });
        if (jobs.size() < maxJobs()) {
            return;
        }
        jobs.values().stream()
                .filter(j -> !Job.STATUS_RUNNING.equals(j.getStatus()))
                .sorted(Comparator.comparingLong(Job::getFinishedAt))
                .limit(jobs.size() - maxJobs() + 1L)
                .forEach(j -> jobs.remove(j.getJobId()));
    }
}
