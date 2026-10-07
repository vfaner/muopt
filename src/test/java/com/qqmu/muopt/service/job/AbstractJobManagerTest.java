package com.qqmu.muopt.service.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractJobManagerTest {

    /** 最小可测子类：任务执行逻辑由测试注入 */
    static class TestManager extends AbstractJobManager<String, String> {
        volatile java.util.function.Function<String, String> logic = p -> "done:" + p;
        volatile CountDownLatch enteredLatch;
        volatile CountDownLatch releaseLatch;

        @Override
        protected String execute(String params, CancelToken token) throws Exception {
            if (enteredLatch != null) {
                enteredLatch.countDown();
                releaseLatch.await(30, TimeUnit.SECONDS);
            }
            if (token.isCancelRequested()) {
                throw new InterruptedException("cancelled");
            }
            return logic.apply(params);
        }

        @Override
        protected String threadPrefix() {
            return "test-job-";
        }
    }

    @Test
    void runsTaskToSuccessAndStoresParamsAndResult() {
        TestManager manager = new TestManager();
        String id = manager.start("hello");

        Job<String, String> job = awaitFinished(manager, id);

        assertEquals("SUCCESS", job.getStatus());
        assertEquals("done:hello", job.getResult());
        assertEquals("hello", job.getParams());
        assertTrue(job.getFinishedAt() >= job.getStartedAt());
    }

    @Test
    void marksFailureWithMessage() {
        TestManager manager = new TestManager();
        manager.logic = p -> { throw new IllegalStateException("boom"); };

        String id = manager.start("x");
        Job<String, String> job = awaitFinished(manager, id);

        assertEquals("FAILED", job.getStatus());
        assertEquals("boom", job.getMessage());
        assertNull(job.getResult());
    }

    @Test
    void cancelsRunningTask() throws Exception {
        TestManager manager = new TestManager();
        manager.enteredLatch = new CountDownLatch(1);
        manager.releaseLatch = new CountDownLatch(1);

        String id = manager.start("blocking");
        assertTrue(manager.enteredLatch.await(5, TimeUnit.SECONDS));

        assertTrue(manager.cancel(id));
        manager.releaseLatch.countDown();

        Job<String, String> job = awaitFinished(manager, id);
        assertEquals("CANCELLED", job.getStatus());
    }

    @Test
    void cancelMissingJobReturnsFalse() {
        assertFalse(new TestManager().cancel("nope"));
    }

    @Test
    void getMissingJobReturnsNull() {
        assertNull(new TestManager().get("nope"));
    }

    @Test
    void itemCountHookCountsListResultsByDefaultNullOtherwise() {
        TestManager manager = new TestManager();
        assertNull(manager.callItemCount("not-a-list"));
    }

    @Test
    void prunesExpiredJobs() {
        // maxJobs=2 的子类：第 3 个任务提交时淘汰最早的已结束任务
        TestManager small = new TestManager() {
            @Override
            protected int maxJobs() {
                return 2;
            }
        };
        String id1 = small.start("a");
        awaitFinished(small, id1);
        String id2 = small.start("b");
        awaitFinished(small, id2);
        String id3 = small.start("c");
        awaitFinished(small, id3);

        assertNull(small.get(id1), "最早的任务应被淘汰");
        assertEquals("SUCCESS", small.get(id2).getStatus());
        assertEquals("SUCCESS", small.get(id3).getStatus());
    }

    private Job<String, String> awaitFinished(TestManager manager, String id) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Job<String, String> job = manager.get(id);
            if (!"RUNNING".equals(job.getStatus())) {
                return job;
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("任务未在 5 秒内结束: " + manager.get(id));
    }
}
