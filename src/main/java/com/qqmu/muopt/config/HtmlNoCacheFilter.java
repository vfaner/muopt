package com.qqmu.muopt.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * HTML 页面与 page-state.js 强制每次回源校验（no-cache：可缓存但必须先验证）。
 *
 * <p>静态资源默认只返回 Last-Modified、没有任何 Cache-Control 头，浏览器会按
 * 「启发式缓存」直接复用旧页面且不回源——升级后用户点菜单看到的可能仍是旧版
 * HTML（旧侧边栏、旧 JS），必须强刷才能更新。加上 no-cache 后浏览器每次带
 * If-Modified-Since 校验，未变更返回 304（开销极小），变更立即生效。
 * /libs 下的第三方库不受影响，继续走浏览器缓存。
 */
@Component
public class HtmlNoCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.endsWith(".html") || uri.equals("/") || uri.endsWith("/page-state.js")) {
            response.setHeader("Cache-Control", "no-cache");
        }
        filterChain.doFilter(request, response);
    }
}
