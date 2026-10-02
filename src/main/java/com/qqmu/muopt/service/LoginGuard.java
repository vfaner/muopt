package com.qqmu.muopt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一账号连续失败 {@value #MAX_FAILURES} 次后锁定 {@value #LOCK_MINUTES} 分钟。
 *
 * <p>按账号（不区分大小写）在内存中计数，登录成功后清零。进程重启会重置计数，
 * 对本机单用户工具而言足够；如要跨重启保留或加 IP 维度，再落库即可。
 */
@Slf4j
@Component
public class LoginGuard {

    public static final int MAX_FAILURES = 5;
    public static final long LOCK_MINUTES = 30;

    private static final long LOCK_MILLIS = LOCK_MINUTES * 60_000L;

    /** key 为规范化账号（trim + 小写），value 为其失败计数与锁定截止时间 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 剩余锁定分钟数；0 表示未锁定。会顺带清理已过期的锁定状态。 */
    public int lockRemaining(String username) {
        Attempt a = attempts.get(normalize(username));
        if (a == null) {
            return 0;
        }
        synchronized (a) {
            return lockedMinutes(a, System.currentTimeMillis());
        }
    }

    /**
     * 记录一次登录失败。返回本次失败是否触发锁定：触发返回锁定分钟数，否则返回 0。
     * 已处于锁定期内再次尝试不会继续累加计数，也不会顺延锁定时长。
     */
    public int recordFailure(String username) {
        Attempt a = attempts.computeIfAbsent(normalize(username), k -> new Attempt());
        synchronized (a) {
            long now = System.currentTimeMillis();
            int locked = lockedMinutes(a, now);
            if (locked > 0) {
                return locked;
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockUntil = now + LOCK_MILLIS;
                a.failures = 0;
                log.warn("账号「{}」连续登录失败 {} 次，已锁定 {} 分钟", normalize(username), MAX_FAILURES, LOCK_MINUTES);
                return (int) LOCK_MINUTES;
            }
            return 0;
        }
    }

    /** 剩余可尝试次数（不含刚记录的那次失败）；已锁定返回 0 */
    public int remainingAttempts(String username) {
        Attempt a = attempts.get(normalize(username));
        if (a == null) {
            return MAX_FAILURES;
        }
        synchronized (a) {
            long now = System.currentTimeMillis();
            if (a.lockUntil > 0 && now < a.lockUntil) {
                return 0;
            }
            return Math.max(0, MAX_FAILURES - a.failures);
        }
    }

    /** 登录成功后清零该账号的失败记录 */
    public void clear(String username) {
        attempts.remove(normalize(username));
    }

    private int lockedMinutes(Attempt a, long now) {
        if (a.lockUntil > 0 && now < a.lockUntil) {
            return (int) Math.ceil((a.lockUntil - now) / 60000.0);
        }
        if (a.lockUntil > 0) {
            a.failures = 0;
            a.lockUntil = 0;
        }
        return 0;
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static class Attempt {
        int failures;
        long lockUntil;
    }
}