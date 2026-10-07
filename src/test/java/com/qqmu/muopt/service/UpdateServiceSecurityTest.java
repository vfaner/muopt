package com.qqmu.muopt.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceSecurityTest {

    private HttpServer server;
    private String shaUrl;
    private UpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new UpdateService("1.1.0");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        shaUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/muopt.jar.sha256";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesPlainHexShaFile() {
        String hex = "a".repeat(64);
        assertEquals(hex, UpdateService.parseSha256(hex));
    }

    @Test
    void parsesSha256sumFormatWithFilename() {
        String hex = "b".repeat(64);
        assertEquals(hex, UpdateService.parseSha256(hex + "  muopt.jar"));
    }

    @Test
    void rejectsGarbageShaFile() {
        assertThrows(IllegalArgumentException.class, () -> UpdateService.parseSha256("not-a-hash"));
    }

    @Test
    void verifyDownloadAcceptsMatchingHash(@TempDir Path tmp) throws Exception {
        byte[] jarBytes = "fake-jar-content".repeat(100_000).getBytes(StandardCharsets.UTF_8);
        File file = tmp.resolve("muopt.jar.update").toFile();
        Files.write(file.toPath(), jarBytes);
        String hex = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(jarBytes));
        serve("/muopt.jar.sha256", (hex + "  muopt.jar\n").getBytes(StandardCharsets.UTF_8));

        service.verifyDownload(file, shaUrl);
        assertTrue(file.exists());
    }

    @Test
    void verifyDownloadRejectsMismatchedHashAndDeletesFile(@TempDir Path tmp) throws Exception {
        File file = tmp.resolve("muopt.jar.update").toFile();
        Files.write(file.toPath(), new byte[2_000_000]);
        serve("/muopt.jar.sha256", ("c".repeat(64) + "\n").getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> service.verifyDownload(file, shaUrl));
        assertFalse(file.exists(), "哈希不匹配必须删除下载文件，防止被重启脚本使用");
    }

    @Test
    void verifyDownloadFailsWhenShaAssetMissing(@TempDir Path tmp) throws Exception {
        File file = tmp.resolve("muopt.jar.update").toFile();
        Files.write(file.toPath(), new byte[2_000_000]);
        // 服务端不提供 sha 文件（404）
        assertThrows(IllegalStateException.class, () -> service.verifyDownload(file, shaUrl));
        assertFalse(file.exists());
    }

    @Test
    void hostCheckAcceptsLoopbackNamesWithOrWithoutPort() {
        assertTrue(UpdateService.isLocalhostHost("127.0.0.1:8080"));
        assertTrue(UpdateService.isLocalhostHost("localhost:8080"));
        assertTrue(UpdateService.isLocalhostHost("[::1]:8080"));
    }

    @Test
    void hostCheckRejectsNonLoopbackAndLookalikes() {
        assertFalse(UpdateService.isLocalhostHost("evil.com:8080"));
        assertFalse(UpdateService.isLocalhostHost("127.0.0.1.evil.com"));
        assertFalse(UpdateService.isLocalhostHost("localhost.evil.com"));
        assertFalse(UpdateService.isLocalhostHost(null));
        assertFalse(UpdateService.isLocalhostHost(""));
    }

    private void serve(String path, byte[] body) {
        server.createContext(path, ex -> {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }
}
