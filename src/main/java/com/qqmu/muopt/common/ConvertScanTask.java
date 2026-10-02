package com.qqmu.muopt.common;

import lombok.Data;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class ConvertScanTask {
    private String taskId;
    private String path;
    private String targetDb;
    /** 扫描 + AI 阶段会由多个线程更新，需保证可见性 */
    private volatile int progress;
    private volatile String status; // "running", "completed", "failed"
    private List<ConversionItem> items;
    private long startTime;
    private long endTime;

    // ======== AI 自动优化 ========
    /** 本次扫描是否启用 AI 优化 */
    private boolean enableAi;
    /** 当前阶段："scanning" 扫描中 / "optimizing" AI优化中 / "done" */
    private volatile String phase = "scanning";
    /** 计划交给 AI 优化的条目数（受上限约束，可能小于 items 总数） */
    private volatile int aiTotal;
    /** 因超出上限而未做 AI 优化的条目数 */
    private volatile int aiSkipped;
    /** AI 阶段的提示信息（例如未配置 AI 而跳过） */
    private volatile String aiMessage;

    private final AtomicInteger aiDoneCount = new AtomicInteger();
    private final AtomicInteger aiFailedCount = new AtomicInteger();
    private final AtomicInteger aiAppliedCount = new AtomicInteger();

    public ConvertScanTask(String taskId, String path, String targetDb) {
        this(taskId, path, targetDb, false);
    }

    public ConvertScanTask(String taskId, String path, String targetDb, boolean enableAi) {
        this.taskId = taskId;
        this.path = path;
        this.targetDb = targetDb;
        this.enableAi = enableAi;
        this.progress = 0;
        this.status = "running";
        this.items = new CopyOnWriteArrayList<>();
        this.startTime = System.currentTimeMillis();
    }

    public void addProgress(int increment) {
        this.progress = Math.min(100, this.progress + increment);
    }

    public void addItem(ConversionItem item) {
        this.items.add(item);
    }

    /** 已完成 AI 调用的条目数（含失败） */
    public int getAiDone() {
        return aiDoneCount.get();
    }

    public int incrementAiDone() {
        return aiDoneCount.incrementAndGet();
    }

    /** AI 调用失败或结果被安全校验拒绝的条目数 */
    public int getAiFailed() {
        return aiFailedCount.get();
    }

    public void incrementAiFailed() {
        aiFailedCount.incrementAndGet();
    }

    /** AI 结果通过校验、实际替换了规则转换结果的条目数 */
    public int getAiApplied() {
        return aiAppliedCount.get();
    }

    public void incrementAiApplied() {
        aiAppliedCount.incrementAndGet();
    }

    public void complete() {
        this.status = "completed";
        this.phase = "done";
        this.progress = 100;
        this.endTime = System.currentTimeMillis();
    }

    public void fail() {
        this.status = "failed";
        this.phase = "done";
        this.endTime = System.currentTimeMillis();
    }
}
