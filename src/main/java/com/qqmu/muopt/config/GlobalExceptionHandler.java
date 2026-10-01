package com.qqmu.muopt.config;

import com.qqmu.muopt.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Result<?> handleIllegalState(IllegalStateException e) {
        log.warn("业务状态不允许: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统异常: " + e.getMessage());
    }
}
