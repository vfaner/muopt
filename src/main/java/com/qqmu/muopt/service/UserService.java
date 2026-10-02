package com.qqmu.muopt.service;

import com.qqmu.muopt.entity.User;
import com.qqmu.muopt.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 登录用户服务：默认账号初始化、登录校验、个人资料修改。
 *
 * <p>密码用 BCrypt 单向哈希，无需复用 CryptoUtil 的可逆加密（那里用于保存外部
 * 数据库密码 / AI API Key，需解密后原样送出；这里的密码只需比对，不可解更安全）。
 */
@Slf4j
@Service
public class UserService implements ApplicationRunner {

    static final String DEFAULT_USERNAME = "admin";
    static final String DEFAULT_PASSWORD = "123456";
    static final String DEFAULT_NICKNAME = "管理员";

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaultAdmin();
    }

    /** 首次启动无任何用户时，初始化默认账号 admin / 123456 */
    private void ensureDefaultAdmin() {
        if (userRepository.count() > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(DEFAULT_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        admin.setNickname(DEFAULT_NICKNAME);
        userRepository.save(admin);
        log.info("已初始化默认账号 {}（密码 {}），请登录后尽快在「个人中心」修改", DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    /** 校验账号密码，成功返回用户，失败返回 null */
    public User authenticate(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        return userRepository.findByUsername(username.trim())
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElse(null);
    }

    public Optional<User> findById(Long id) {
        return id == null ? Optional.empty() : userRepository.findById(id);
    }

    /**
     * 更新个人资料。密码传空字符串 / null 表示保持不变。
     *
     * @return 账号被占用时返回提示文案；成功返回 null
     */
    @Transactional
    public String updateProfile(Long userId, String nickname, String newUsername, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("用户不存在或会话已失效"));

        String username = newUsername == null ? "" : newUsername.trim();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        if (!username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
            return "账号已被占用，请更换";
        }
        user.setUsername(username);

        String nick = nickname == null ? "" : nickname.trim();
        user.setNickname(nick.isEmpty() ? username : nick);

        if (newPassword != null && !newPassword.isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(newPassword));
        }
        userRepository.save(user);
        return null;
    }
}