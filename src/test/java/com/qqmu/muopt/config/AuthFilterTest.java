package com.qqmu.muopt.config;

import com.qqmu.muopt.controller.AuthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 回归：AuthFilter 对未分类路径必须默认拒绝（旧实现末尾直接放行，
 * 未来新增的非 /api 端点会绕过登录网关）。
 */
class AuthFilterTest {

    private AuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private boolean[] chainCalled;

    @BeforeEach
    void setUp() {
        filter = new AuthFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chainCalled = new boolean[]{false};
    }

    private void invoke() throws Exception {
        filter.doFilter(request, response,
                (req, res) -> chainCalled[0] = true);
    }

    @Test
    void blocksUnclassifiedPathWhenUnauthenticated() throws Exception {
        request.setRequestURI("/some/new/endpoint");
        invoke();

        assertFalse(chainCalled[0], "未匹配任何规则的路径不得放行");
        assertEquals(404, response.getStatus());
    }

    @Test
    void stillAllowsErrorDispatch() throws Exception {
        // 容器错误分发到 /error：不能被默认拒绝拦住，否则异常时连错误页都出不来
        request.setRequestURI("/error");
        invoke();
        assertTrue(chainCalled[0]);
    }

    @Test
    void keepsExistingApi401Behavior() throws Exception {
        request.setRequestURI("/api/secret");
        invoke();
        assertFalse(chainCalled[0]);
        assertEquals(401, response.getStatus());
    }

    @Test
    void passesThroughWhenLoggedIn() throws Exception {
        request.setRequestURI("/some/new/endpoint");
        request.getSession().setAttribute(AuthController.SESSION_USER_ID, 1L);
        invoke();
        assertTrue(chainCalled[0]);
    }
}
