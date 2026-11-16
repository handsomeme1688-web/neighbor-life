package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Requires a valid authenticated session for protected requests.
 *
 * @author NeighborLife
 */
public class LoginInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    public LoginInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        boolean publicRead = "GET".equals(request.getMethod()) && (path.startsWith("/shop/") || path.startsWith("/blogs/")
                || path.startsWith("/shop-type") || path.startsWith("/voucher/") || path.equals("/blog/hot")
                || path.startsWith("/actuator/health") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui") || path.startsWith("/webjars/"));
        boolean publicPost = "POST".equals(request.getMethod()) && (path.equals("/user/code")
                || path.equals("/user/login") || path.equals("/payments/wechat/notify"));
        if (publicRead || publicPost) { return true; }
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(), Result.fail("UNAUTHENTICATED", "请先登录"));
            return false;
        }
        return true;
    }
}
