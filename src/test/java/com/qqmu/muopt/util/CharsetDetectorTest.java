package com.qqmu.muopt.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharsetDetectorTest {

    @Test
    void detectsUtf8WithoutBom() {
        byte[] bytes = "SELECT * FROM 用户 WHERE 姓名 = '张三'".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, CharsetDetector.detect(bytes));
    }

    @Test
    void detectsUtf8WithBom() {
        byte[] text = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[text.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(text, 0, withBom, 3, text.length);
        assertEquals(StandardCharsets.UTF_8, CharsetDetector.detect(withBom));
    }

    @Test
    void fallsBackToGbkForNonUtf8ChineseBytes() {
        // GBK 编码的中文对 UTF-8 严格解码非法
        byte[] bytes = "SELECT * FROM 用户表 WHERE 姓名 = '张三'".getBytes(Charset.forName("GBK"));
        assertEquals(Charset.forName("GBK"), CharsetDetector.detect(bytes));
    }

    @Test
    void defaultsToUtf8ForEmptyInput() {
        assertEquals(StandardCharsets.UTF_8, CharsetDetector.detect(new byte[0]));
    }

    @Test
    void keepsRoundTripForAscii() {
        byte[] bytes = "SELECT id FROM t WHERE name = 'abc'".getBytes(StandardCharsets.US_ASCII);
        assertEquals(StandardCharsets.UTF_8, CharsetDetector.detect(bytes));
    }
}
