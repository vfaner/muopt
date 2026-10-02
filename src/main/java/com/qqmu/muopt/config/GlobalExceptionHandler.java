package com.qqmu.muopt.config;

import com.qqmu.muopt.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 浏览器/DevTools 会主动探测 /.well-known/appspecific/com.chrome.devtools.json
     * 等并不存在的静态资源，缺省落到 ResourceHttpRequestHandler 抛
     * NoResourceFoundException，被兜底 handler 记成「系统异常」刷错误日志。
     * 这类请求属于正常的 404，单独接住、降级为 debug。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<?> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return Result.error(404, "资源不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error("系统异常: " + e.getMessage());
    }
}
