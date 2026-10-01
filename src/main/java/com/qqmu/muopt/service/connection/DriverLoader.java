package com.qqmu.muopt.service.connection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 运行时从用户提供的 jar 加载 JDBC 驱动并注册到 {@link DriverManager}（移植自 synctool）。
 *
 * <p>类加载器按 jar 路径缓存，避免重复连接同一自定义库时每次尝试都泄漏一个加载器，
 * 也保证驱动的静态状态只初始化一次。
 */
@Component
@Slf4j
public class DriverLoader {

    /** key 为 jar 路径（目录则为其规范路径） */
    private final Map<String, URLClassLoader> loaderCache = new ConcurrentHashMap<>();

    /** 驱动类名 -> 已注册到 DriverManager 的 shim */
    private final Map<String, DriverShim> registered = new ConcurrentHashMap<>();

    /**
     * 确保驱动类可加载且已注册。
     *
     * @param driverClassName 驱动实现类全限定名
     * @param jarPath         jar 文件或 jar 目录路径；为空时要求驱动已在应用 classpath 上
     * @return 拥有该驱动的类加载器，调用方可在建池前临时设置为线程上下文加载器
     */
    public ClassLoader ensureDriverLoaded(String driverClassName, String jarPath) {
        if (!StringUtils.hasText(driverClassName)) {
            throw new IllegalArgumentException("驱动类名不能为空");
        }

        DriverShim existing = registered.get(driverClassName);
        if (existing != null) {
            return existing.getDelegate().getClass().getClassLoader();
        }

        if (!StringUtils.hasText(jarPath)) {
            try {
                Class.forName(driverClassName);
                log.debug("驱动 {} 已在应用 classpath 上", driverClassName);
                return getClass().getClassLoader();
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "驱动 " + driverClassName + " 不在 classpath 上，且未提供驱动 jar 路径，请在连接设置中填写", e);
            }
        }

        URLClassLoader loader = loaderCache.computeIfAbsent(canonical(jarPath), this::buildLoader);

        try {
            Class<?> driverClass = Class.forName(driverClassName, true, loader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverShim shim = new DriverShim(driver);
            DriverManager.registerDriver(shim);
            registered.put(driverClassName, shim);
            log.info("已从 {} 动态注册驱动 {}", jarPath, driverClassName);
            return loader;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("在 " + jarPath + " 中未找到驱动类 " + driverClassName, e);
        } catch (SQLException e) {
            throw new IllegalStateException("注册驱动 " + driverClassName + " 失败", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("实例化驱动 " + driverClassName + " 失败，可能需要其他加载方式", e);
        }
    }

    private URLClassLoader buildLoader(String jarPath) {
        List<URL> urls = new ArrayList<>();
        // 多路径用 ; 或 , 分隔（不用冒号，避免误伤 Windows 盘符 C:\...）
        for (String part : jarPath.split("[;,]")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            File file = new File(trimmed);
            if (!file.exists()) {
                throw new IllegalArgumentException("驱动 jar 路径不存在: " + trimmed);
            }
            if (file.isDirectory()) {
                File[] jars = file.listFiles(f -> f.getName().toLowerCase().endsWith(".jar"));
                if (jars == null || jars.length == 0) {
                    throw new IllegalArgumentException("目录中未找到 jar 文件: " + trimmed);
                }
                for (File jar : jars) {
                    urls.add(toUrl(jar));
                }
            } else {
                urls.add(toUrl(file));
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("没有可用的驱动 jar: " + jarPath);
        }
        // 父加载器是应用加载器，驱动可以看到 java.sql.* 与内置类
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    private URL toUrl(File file) {
        try {
            return file.toURI().toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("非法驱动 jar 路径: " + file, e);
        }
    }

    private String canonical(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }

    /** 列出 jar 中声明的候选驱动类名（读取 META-INF/services/java.sql.Driver），帮助用户填表 */
    public List<String> discoverDriverClasses(String jarPath) {
        List<String> found = new ArrayList<>();
        for (String part : jarPath.split("[;,]")) {
            File file = new File(part.trim());
            if (!file.isFile()) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
                // 合规驱动通过 ServiceLoader 描述文件声明自己
                JarEntry svc = jar.getJarEntry("META-INF/services/java.sql.Driver");
                if (svc != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(jar.getInputStream(svc), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String cls = line.trim();
                            if (!cls.isEmpty() && !cls.startsWith("#") && !found.contains(cls)) {
                                found.add(cls);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("扫描 {} 中的驱动失败: {}", part, e.getMessage());
            }
        }
        return found;
    }
}
