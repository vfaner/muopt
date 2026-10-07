package com.qqmu.muopt.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 源码文件编码探测：BOM 优先，其次严格 UTF-8 试探，失败回退 GBK。
 *
 * <p>背景：本工具的目标工程既有现代 UTF-8 项目，也有大量 GBK 的老工程 / 信创工程。
 * 一律按 UTF-8 解码会把中文变成 U+FFFD，写回时整文件被破坏。故所有读取源文件的
 * 路径都应先用本类探测，再按探测到的编码解码，写回时保持原编码。
 */
public final class CharsetDetector {

    private static final Charset GBK = Charset.forName("GBK");

    private CharsetDetector() {
    }

    public static Charset detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        if (hasUtf8Bom(bytes)) {
            return StandardCharsets.UTF_8;
        }
        if (isStrictUtf8(bytes)) {
            return StandardCharsets.UTF_8;
        }
        return GBK;
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }

    /** 严格 UTF-8 解码：任何非法字节序列都判定为非 UTF-8（默认的 lenient 解码会吞掉错误） */
    private static boolean isStrictUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
