package com.qqmu.muopt.service.convert;

import com.qqmu.muopt.service.AiService;
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
 * 手工转换「AI 方言润色」的后台任务管理器。
 *
 * <p>规则转换毫秒级完成，但 AI 润色要调用大模型、可能等几十秒；同步请求在用户切菜单/刷新时
 * 会被浏览器中断，页面只剩一直转圈的按钮。改为「提交任务 → 轮询状态」后，任务在服务端执行，
 * 前端凭暂存的 jobId 回来继续轮询即可拿到结果。
 */
@Slf4j
@Component
public class ConvertPolishJobManager {

    /** 已完成任务结果的保留时长（30 分钟） */
    private static final long RETAIN_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static final int MAX_JOBS = 30;

    private final ExecutorService jobPool;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    private final SqlConverter sqlConverter;
    private final AiService aiService;

    @Autowired
    public ConvertPolishJobManager(SqlConverter sqlConverter, AiService aiService) {
        this.sqlConverter = sqlConverter;
        this.aiService = aiService;
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "convert-polish-job-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        this.jobPool = Executors.newFixedThreadPool(2, tf);
    }

    /** 提交手工转换润色任务，立即返回 jobId（规则转换 + AI 润色都在后台执行） */
    public String start(String sourceSql, String targetDb) {
        pruneFinished();
        String jobId = UUID.randomUUID().toString().replace("-", "");
        Job job = new Job(jobId, sourceSql, targetDb);
        jobs.put(jobId, job);
        Future<?> future = jobPool.submit(() -> run(job));
        job.setFuture(future);
        log.info("手工转换 AI 润色任务已提交: {}", jobId);
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
        job.markCancelled("已取消润色");
        return true;
    }

    private void run(Job job) {
        try {
            String convertedSql = sqlConverter.convert(job.getSourceSql(), job.getTargetDb());
            String polished = aiService.polishConvertedSql(convertedSql, job.getTargetDb());
            // AI 请求被中断时底层会返回失败结果而非抛异常，这里以取消标志为准
            if (job.isCancelRequested() || Thread.currentThread().isInterrupted()) {
                job.markCancelled("已取消润色");
            } else {
                job.markSuccess(polished);
            }
        } catch (Exception e) {
            if (job.isCancelRequested() || isCancellation(e)) {
                job.markCancelled("已取消润色");
            } else {
                log.warn("手工转换润色任务 {} 失败: {}", job.getJobId(), e.getMessage());
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

    /** 一个手工转换润色任务的状态与结果 */
    @Getter
    public static class Job {
        static final String STATUS_RUNNING = "RUNNING";
        static final String STATUS_SUCCESS = "SUCCESS";
        static final String STATUS_FAILED = "FAILED";
        static final String STATUS_CANCELLED = "CANCELLED";

        private final String jobId;
        private final String sourceSql;
        private final String targetDb;
        private final long startedAt;

        private volatile String status = STATUS_RUNNING;
        private volatile String message;
        private volatile long finishedAt;
        /** 润色后的最终 SQL（仅 SUCCESS 时有值） */
        private volatile String convertedSql;
        private volatile Future<?> future;
        private volatile boolean cancelRequested;

        Job(String jobId, String sourceSql, String targetDb) {
            this.jobId = jobId;
            this.sourceSql = sourceSql;
            this.targetDb = targetDb;
            this.startedAt = System.currentTimeMillis();
        }

        void setFuture(Future<?> future) {
            this.future = future;
        }

        synchronized void markSuccess(String convertedSql) {
            if (!STATUS_RUNNING.equals(status)) {
                return;
            }
            this.convertedSql = convertedSql;
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

        private void finish(String status, String message) {
            this.status = status;
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
        }
    }
}