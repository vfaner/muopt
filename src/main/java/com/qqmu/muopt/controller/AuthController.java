package com.qqmu.muopt.controller;

import com.qqmu.muopt.common.Result;
import com.qqmu.muopt.entity.User;
import com.qqmu.muopt.service.LoginGuard;
import com.qqmu.muopt.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录 / 登出 / 当前用户 / 个人资料修改。
 *
 * <p>会话认证：登录成功后在 {@link HttpSession} 存用户 id，由 {@code AuthFilter} 统一
 * 拦截未登录访问，前端无需自行维护 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 会话中存放登录用户 id 的键，AuthFilter 依赖它判断登录态 */
    public static final String SESSION_USER_ID = "muopt.user.id";

    private final UserService userService;
    private final LoginGuard loginGuard;

    public AuthController(UserService userService, LoginGuard loginGuard) {
        this.userService = userService;
        this.loginGuard = loginGuard;
    }

    @PostMapping("/login")
    public Result<UserView> login(@RequestBody LoginRequest request, HttpServletRequest req) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        int locked = loginGuard.lockRemaining(username);
        if (locked > 0) {
            return Result.error(423, "登录失败次数过多，账户已锁定，请 " + locked + " 分钟后再试");
        }

        User user = userService.authenticate(username, password);
        if (user == null) {
            if (loginGuard.recordFailure(username) > 0) {
                return Result.error(423, "连续登录失败 " + LoginGuard.MAX_FAILURES + " 次，账户已锁定 " + LoginGuard.LOCK_MINUTES + " 分钟，请稍后再试");
            }
            int left = loginGuard.remainingAttempts(username);
            return Result.error(401, "账号或密码错误，还可尝试 " + left + " 次");
        }

        loginGuard.clear(username);
        req.getSession(true).setAttribute(SESSION_USER_ID, user.getId());
        return Result.success(UserView.of(user));
    }

    @PostMapping("/logout")
    public Result<?> logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success(true);
    }

    @GetMapping("/me")
    public Result<UserView> me(HttpServletRequest req) {
        User user = currentUser(req);
        if (user == null) {
            return Result.error(401, "未登录");
        }
        return Result.success(UserView.of(user));
    }

    /**
     * 更新个人资料。保存成功后立即失效会话（强制退出），前端引导重新登录。
     */
    @PostMapping("/profile")
    public Result<?> updateProfile(@RequestBody ProfileRequest request, HttpServletRequest req) {
        User user = currentUser(req);
        if (user == null) {
            return Result.error(401, "未登录或会话已过期，请重新登录");
        }
        String conflict = userService.updateProfile(user.getId(),
                request.getNickname(), request.getUsername(), request.getPassword());
        if (conflict != null) {
            return Result.error(400, conflict);
        }
        // 账号 / 密码可能已变，会话强制失效，让用户用新凭据重新登录
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success(null);
    }

    private User currentUser(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return null;
        }
        Object id = session.getAttribute(SESSION_USER_ID);
        if (id == null) {
            return null;
        }
        return userService.findById((Long) id).orElse(null);
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    public static class ProfileRequest {
        private String nickname;
        private String username;
        /** 留空表示不修改密码 */
        private String password;
    }

    /** 对外暴露的用户信息，永不包含密码 */
    @Data
    public static class UserView {
        private Long id;
        private String username;
        private String nickname;

        static UserView of(User user) {
            UserView v = new UserView();
            v.id = user.getId();
            v.username = user.getUsername();
            v.nickname = user.getNickname();
            return v;
        }
    }
}