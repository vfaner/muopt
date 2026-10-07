package com.qqmu.muopt.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 修复回归：Spring Boot 3.2 fat jar 中本类 codeSource 是
 * {@code jar:nested:/path/muopt.jar/!BOOT-INF/classes!/}，旧逻辑 new File(location)
 * 必失败，导致「java -jar」方式下自更新永远报"不是以可执行 jar 运行"。
 */
class RunningJarLocatorTest {

    @Test
    void parsesBoot32NestedJarLocation() {
        String location = "jar:nested:/private/tmp/muopt-verify/muopt.jar/!BOOT-INF/classes!/";
        assertEquals("/private/tmp/muopt-verify/muopt.jar",
                UpdateService.extractJarPath(location));
    }

    @Test
    void parsesLegacyJarFileLocation() {
        String location = "jar:file:/opt/app/muopt.jar!/BOOT-INF/classes!/";
        assertEquals("/opt/app/muopt.jar", UpdateService.extractJarPath(location));
    }

    @Test
    void parsesPlainJarFileUrl() {
        assertEquals("/opt/muopt.jar",
                UpdateService.extractJarPath("file:/opt/muopt.jar"));
    }

    @Test
    void returnsNullWhenNoJarPresent() {
        assertNull(UpdateService.extractJarPath("file:/Users/foo/project/target/classes/"));
        assertNull(UpdateService.extractJarPath(null));
        assertNull(UpdateService.extractJarPath(""));
    }

    @Test
    void jarFromCommandLineArgsFindsJarAfterDashJar() {
        assertEquals("/opt/muopt.jar", UpdateService.jarFromArgs(
                List.of("-Xmx512m", "-jar", "/opt/muopt.jar", "--spring.profiles.active=x")));
    }

    @Test
    void jarFromArgsReturnsNullWithoutJar() {
        assertNull(UpdateService.jarFromArgs(List.of("-cp", "target/classes", "Main")));
    }
}
