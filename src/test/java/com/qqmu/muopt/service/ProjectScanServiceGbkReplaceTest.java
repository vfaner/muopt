package com.qqmu.muopt.service;

import com.qqmu.muopt.common.ScanItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * H1 回归：GBK 工程文件在替换写回时，编码与中文必须保持完好，
 * 且 .bak 备份必须是未经解码的原始字节（老实现备份同样损坏，用户无件可恢复）。
 */
class ProjectScanServiceGbkReplaceTest {

    @TempDir
    Path tmp;

    @Test
    void replaceOnGbkFilePreservesChineseAndBacksUpRawBytes() throws Exception {
        ProjectScanService service = new ProjectScanService(
                mock(SqlOptimizerService.class),
                mock(AiService.class),
                mock(LocalRewriteService.class));

        @SuppressWarnings("unchecked")
        Set<String> roots = (Set<String>) ReflectionTestUtils.getField(service, "scannedRoots");
        roots.add(tmp.toRealPath().toString());

        String original = "-- 查询用户脚本\nSELECT id FROM 用户 WHERE 姓名 = '张三';\n";
        Path file = tmp.resolve("q.sql");
        Files.write(file, original.getBytes("GBK"));

        String raw = "SELECT id FROM 用户 WHERE 姓名 = '张三'";
        ScanItem item = new ScanItem();
        item.setFilePath(file.toString());
        item.setSourceType("SQL_FILE");
        item.setRawText(raw);
        item.setRawOffset(original.indexOf(raw));

        String optimized = "SELECT id, 姓名 FROM 用户 WHERE 姓名 = '张三'";
        service.replace(item, optimized);

        byte[] after = Files.readAllBytes(file);
        // 文件仍是 GBK，除被替换片段外中文全部完好
        assertEquals(original.replace(raw, optimized), new String(after, Charset.forName("GBK")));

        Path bak = Files.list(tmp)
                .filter(p -> p.getFileName().toString().contains(".bak"))
                .findFirst().orElseThrow(() -> new AssertionError("未生成 .bak 备份"));
        // 备份内容是原始 GBK 字节，可完整恢复
        assertArrayEquals(original.getBytes("GBK"), Files.readAllBytes(bak));
    }
}
