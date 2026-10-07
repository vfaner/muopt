package com.qqmu.muopt.config;

import com.qqmu.muopt.controller.AuthController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 登录网关：未登录时——页面请求跳转到登录页，API 请求返回 401。
 *
 * <p>放行清单：登录页、登录相关 API、静态资源（css/js/图片/字体/三方库）。
 * 业务页面一律要求已登录；登录成功后回跳请求指定的目标页（redirect 参数）。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (isLoggedIn(request) || isPublic(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 页面：跳登录页，并带上原目标便于登录后回跳
        if (uri.equals("/") || uri.endsWith(".html") || uri.endsWith("/")) {
            String redirect = URLEncoder.encode(uri, StandardCharsets.UTF_8);
            response.sendRedirect("/login.html?redirect=" + redirect);
            return;
        }

        // API：返回 401，前端据此提示重新登录
        if (uri.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或会话已过期\"}");
            return;
        }

        // 其余未分类路径默认拒绝：白名单之外不放行，保证将来新增端点不会绕过登录网关
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":404,\"message\":\"资源不存在\"}");
    }

    private boolean isLoggedIn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(AuthController.SESSION_USER_ID) != null;
    }

    private boolean isPublic(String uri) {
        return uri.equals("/login.html")
                || uri.equals("/error")
                || uri.startsWith("/api/auth/")
                || uri.startsWith("/libs/")
                || uri.startsWith("/image/")
                || uri.equals("/favicon.ico")
                || uri.endsWith(".css") || uri.endsWith(".js")
                || uri.endsWith(".png") || uri.endsWith(".jpg") || uri.endsWith(".jpeg")
                || uri.endsWith(".svg") || uri.endsWith(".webp") || uri.endsWith(".ico")
                || uri.endsWith(".gif") || uri.endsWith(".woff") || uri.endsWith(".woff2")
                || uri.endsWith(".ttf") || uri.endsWith(".map");
    }
}