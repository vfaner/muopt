package com.qqmu.muopt.config;

import com.qqmu.muopt.common.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 回归：兜底异常处理不得把底层异常原文（SQL、绝对路径、连接串等）回传客户端，
 * 详细信息只允许进入服务端日志。
 */
class GlobalExceptionHandlerTest {

    @Test
    void genericHandlerDoesNotLeakExceptionMessage() {
        String sensitive = "jdbc:h2:file:/Users/rgh/data/muopt;MODE=MySQL 用户表不存在";
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        Result<?> result = handler.handleException(new RuntimeException(sensitive));

        assertFalse(result.getMessage().contains(sensitive), "异常原文不得出现在响应中");
        assertFalse(result.getMessage().contains("/Users/rgh"), "不得泄露绝对路径");
        assertTrue(result.getMessage().contains("稍后") || result.getMessage().contains("重试"),
                "应给出通用提示，而不是空白");
    }
}
