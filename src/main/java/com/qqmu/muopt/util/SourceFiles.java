package com.qqmu.muopt.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 源文件统一读写入口：先读原始字节、探测编码再解码；写回时保持原编码并严格转换。
 *
 * <p>所有会扫描 / 改写用户工程文件的服务都应走本类，禁止直接
 * {@code Files.readString(path, UTF_8)}——GBK 老工程会被解码成 U+FFFD 后整文件破坏。
 */
public final class SourceFiles {

    private SourceFiles() {
    }

    /** 一次读取的结果：原始字节（可用于字节级备份）、探测到的编码、解码文本。 */
    public record Source(byte[] bytes, Charset charset, String text) {
    }

    public static Source read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Charset charset = CharsetDetector.detect(bytes);
        return new Source(bytes, charset, new String(bytes, charset));
    }

    /**
     * 按指定编码写回。无法表示的字符（如 GBK 文件里出现 emoji）直接抛异常，
     * 不做替换字符 / 丢弃，避免静默破坏。
     */
    public static void write(Path path, String text, Charset charset) throws IOException {
        ByteBuffer encoded = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        Files.write(path, out);
    }
}
