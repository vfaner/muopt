package com.qqmu.muopt.service.job;

/**
 * 任务协作式取消标志：cancel() 置标志并由线程池 Future.cancel(true) 中断，
 * 执行逻辑可在关键节点检查 {@link #isCancelRequested()}。
 */
public class CancelToken {

    private volatile boolean cancelRequested;

    public void requestCancel() {
        cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }
}
