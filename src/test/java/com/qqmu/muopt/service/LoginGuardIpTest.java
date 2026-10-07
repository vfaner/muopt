package com.qqmu.muopt.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 回归：登录失败计数按「账号 + 来源 IP」独立。攻击者从自己 IP 的失败
 * 不能再锁定受害者在其正常 IP 上的登录（旧实现只按账号计数，可被恶意锁死 30 分钟）。
 */
class LoginGuardIpTest {

    @Test
    void failuresFromAttackerIpDoNotLockVictimIp() {
        LoginGuard guard = new LoginGuard();

        for (int i = 0; i < LoginGuard.MAX_FAILURES; i++) {
            guard.recordFailure("admin", "10.0.0.66");
        }

        // 攻击者 IP 上该账号确已锁定
        assertTrue(guard.lockRemaining("admin", "10.0.0.66") > 0);
        // 但受害者从本机 / 自己的 IP 登录不受影响——关键修复点
        assertEquals(0, guard.lockRemaining("admin", "127.0.0.1"));
        assertEquals(LoginGuard.MAX_FAILURES, guard.remainingAttempts("admin", "127.0.0.1"));
    }

    @Test
    void tracksEachIpIndependently() {
        LoginGuard guard = new LoginGuard();

        guard.recordFailure("admin", "10.0.0.1");
        guard.recordFailure("admin", "10.0.0.2");

        assertEquals(LoginGuard.MAX_FAILURES - 1, guard.remainingAttempts("admin", "10.0.0.1"));
        assertEquals(LoginGuard.MAX_FAILURES - 1, guard.remainingAttempts("admin", "10.0.0.2"));
    }

    @Test
    void locksSourceIpAfterTooManyFailuresAcrossUsernames() {
        LoginGuard guard = new LoginGuard();
        String attackerIp = "10.0.0.66";

        // 撞库：对同一 IP 上的大量不同账号各试一次
        for (int i = 0; i < LoginGuard.MAX_FAILURES_PER_IP; i++) {
            guard.recordFailure("user" + i, attackerIp);
        }

        assertTrue(guard.lockRemaining("anyone", attackerIp) > 0,
                "单一 IP 跨账号的高频失败必须锁定该 IP，防撞库");
    }

    @Test
    void clearOnlyAffectsGivenUserAndIp() {
        LoginGuard guard = new LoginGuard();
        guard.recordFailure("admin", "10.0.0.1");
        guard.recordFailure("admin", "10.0.0.2");

        guard.clear("admin", "10.0.0.1");

        assertEquals(LoginGuard.MAX_FAILURES, guard.remainingAttempts("admin", "10.0.0.1"));
        assertEquals(LoginGuard.MAX_FAILURES - 1, guard.remainingAttempts("admin", "10.0.0.2"));
    }
}
