package com.qqmu.muopt.service.job;

import lombok.Getter;

import java.util.concurrent.Future;

/**
 * 后台任务的状态与结果。三个 JobManager（优化 / 扫描 / 转换润色）共用本类，
 * 通过泛型参数区分参数与结果类型。
 */
@Getter
public class Job<P, R> {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final String jobId;
    private final P params;
    private final long startedAt;

    private volatile String status = STATUS_RUNNING;
    private volatile String message;
    private volatile long finishedAt;
    private volatile R result;
    /** 结果条目数（仅结果为集合的任务，如扫描；其它任务为 null） */
    private volatile Integer itemCount;
    private volatile Future<?> future;

    private final CancelToken cancelToken = new CancelToken();

    public Job(String jobId, P params) {
        this.jobId = jobId;
        this.params = params;
        this.startedAt = System.currentTimeMillis();
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public synchronized void markSuccess(R result, Integer itemCount) {
        if (!STATUS_RUNNING.equals(status)) {
            return;
        }
        this.result = result;
        this.itemCount = itemCount;
        finish(STATUS_SUCCESS, null);
    }

    public synchronized void markFailed(String message) {
        if (!STATUS_RUNNING.equals(status)) {
            return;
        }
        finish(STATUS_FAILED, message);
    }

    public synchronized void markCancelledIfRunning(String message) {
        if (!STATUS_RUNNING.equals(status)) {
            return;
        }
        finish(STATUS_CANCELLED, message);
    }

    public CancelToken cancelToken() {
        return cancelToken;
    }

    private void finish(String status, String message) {
        this.status = status;
        this.message = message;
        this.finishedAt = System.currentTimeMillis();
    }
}
