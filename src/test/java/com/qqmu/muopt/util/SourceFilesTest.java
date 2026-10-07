package com.qqmu.muopt.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceFilesTest {

    @TempDir
    Path tmp;

    @Test
    void readsGbkFileWithCorrectCharsetAndText() throws Exception {
        Path file = tmp.resolve("gbk.txt");
        String original = "SELECT * FROM 用户表 WHERE 姓名 = '张三'";
        Files.write(file, original.getBytes("GBK"));

        SourceFiles.Source source = SourceFiles.read(file);

        assertEquals(Charset.forName("GBK"), source.charset());
        assertEquals(original, source.text());
    }

    @Test
    void readsUtf8File() throws Exception {
        Path file = tmp.resolve("utf8.txt");
        String original = "SELECT * FROM 用户表";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));

        SourceFiles.Source source = SourceFiles.read(file);

        assertEquals(StandardCharsets.UTF_8, source.charset());
        assertEquals(original, source.text());
    }

    @Test
    void writesBackPreservingOriginalCharset() throws Exception {
        Path file = tmp.resolve("gbk.txt");
        byte[] originalBytes = "SELECT * FROM 用户表".getBytes("GBK");
        Files.write(file, originalBytes);

        SourceFiles.Source source = SourceFiles.read(file);
        String updated = source.text().replace("*", "id, 姓名");
        SourceFiles.write(file, updated, source.charset());

        assertArrayEquals(updated.getBytes("GBK"), Files.readAllBytes(file));
    }

    @Test
    void throwsWhenTextCannotBeRepresentedInTargetCharset() throws Exception {
        Path file = tmp.resolve("gbk.txt");
        // emoji 在 GBK 中无法表示：必须显式失败，不能悄悄写成 '?'
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> SourceFiles.write(file, "SELECT '😀'", Charset.forName("GBK")));
    }
}
