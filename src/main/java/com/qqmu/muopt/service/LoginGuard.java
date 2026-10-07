package com.qqmu.muopt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一「账号 + 来源 IP」连续失败 {@value #MAX_FAILURES} 次后锁定 {@value #LOCK_MINUTES} 分钟；
 * 同一来源 IP 跨账号失败累计 {@value #MAX_FAILURES_PER_IP} 次后同样锁定（防撞库）。
 *
 * <p>计数维度说明：旧版只按账号计数，任何人对 admin 连续失败 5 次即可把真正的用户
 * 锁死 30 分钟（账号 DoS）。现按账号与来源 IP 组合计数——攻击者在自己 IP 上的失败
 * 不影响受害者在正常 IP 登录；同时保留单 IP 的总量上限。
 *
 * <p>计数在内存中，登录成功后清零，进程重启会重置。对本机单用户工具而言足够；
 * 如要跨重启保留，再落库即可。
 */
@Slf4j
@Component
public class LoginGuard {

    public static final int MAX_FAILURES = 5;
    /** 单一来源 IP 跨账号尝试的失败上限，超过锁定该 IP */
    public static final int MAX_FAILURES_PER_IP = 20;
    public static final long LOCK_MINUTES = 30;

    private static final long LOCK_MILLIS = LOCK_MINUTES * 60_000L;

    /** key 为「账号@IP」或「IP」，value 为其失败计数与锁定截止时间 */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 剩余锁定分钟数（账号维度与 IP 维度取较大值）；0 表示未锁定。会顺带清理已过期的锁定状态。 */
    public int lockRemaining(String username, String clientIp) {
        String ip = normalizeIp(clientIp);
        return Math.max(lockedMinutes(attempts.get(userKey(username, ip)), System.currentTimeMillis()),
                lockedMinutes(attempts.get(ipKey(ip)), System.currentTimeMillis()));
    }

    /**
     * 记录一次登录失败：账号维度与 IP 维度各计一次。返回本次失败是否触发锁定：
     * 触发返回锁定分钟数，否则返回 0。已处于锁定期内再次尝试不会继续累加计数，也不会顺延锁定时长。
     */
    public int recordFailure(String username, String clientIp) {
        String ip = normalizeIp(clientIp);
        int userLock = bump(userKey(username, ip), MAX_FAILURES);
        int ipLock = bump(ipKey(ip), MAX_FAILURES_PER_IP);
        return Math.max(userLock, ipLock);
    }

    /** 该账号在该 IP 上的剩余可尝试次数（不含刚记录的那次失败）；已锁定返回 0 */
    public int remainingAttempts(String username, String clientIp) {
        Attempt a = attempts.get(userKey(username, normalizeIp(clientIp)));
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

    /** 登录成功后清零该「账号 + IP」的失败记录 */
    public void clear(String username, String clientIp) {
        attempts.remove(userKey(username, normalizeIp(clientIp)));
    }

    /** 对某个 key 累加一次失败；达到阈值则置锁定并返回锁定分钟数，否则返回 0 */
    private int bump(String key, int threshold) {
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (a) {
            long now = System.currentTimeMillis();
            int locked = lockedMinutes(a, now);
            if (locked > 0) {
                return locked;
            }
            a.failures++;
            if (a.failures >= threshold) {
                a.lockUntil = now + LOCK_MILLIS;
                a.failures = 0;
                log.warn("登录失败已达 {} 次，key「{}」锁定 {} 分钟", threshold, key, LOCK_MINUTES);
                return (int) LOCK_MINUTES;
            }
            return 0;
        }
    }

    /** 剩余锁定分钟数；锁已过期则重置计数。attempt 为 null 时返回 0 */
    private int lockedMinutes(Attempt a, long now) {
        if (a == null) {
            return 0;
        }
        if (a.lockUntil > 0 && now < a.lockUntil) {
            return (int) Math.ceil((a.lockUntil - now) / 60000.0);
        }
        if (a.lockUntil > 0) {
            a.failures = 0;
            a.lockUntil = 0;
        }
        return 0;
    }

    private String userKey(String username, String ip) {
        return "user:" + normalize(username) + "@" + ip;
    }

    private String ipKey(String ip) {
        return "ip:" + ip;
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        return clientIp.trim().toLowerCase(Locale.ROOT);
    }

    private static class Attempt {
        int failures;
        long lockUntil;
    }
}
