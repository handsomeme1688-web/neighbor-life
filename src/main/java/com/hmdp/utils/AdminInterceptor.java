package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the configured administration identity allowlist.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
public class AdminInterceptor implements HandlerInterceptor {

    private final Set<Long> adminIds;
    private final ObjectMapper objectMapper;
    private com.hmdp.security.AccessService accessService;

    public AdminInterceptor(String adminUserIds, ObjectMapper objectMapper, com.hmdp.security.AccessService access) {
        this(adminUserIds, objectMapper);
        this.accessService = access;
    }

    public AdminInterceptor(String adminUserIds, ObjectMapper objectMapper) {
        this.adminIds = Arrays.stream(adminUserIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(Long::valueOf).collect(Collectors.toSet());
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        boolean administrativePath = request.getRequestURI().startsWith("/ops/")
                || request.getRequestURI().startsWith("/actuator/") && !request.getRequestURI().startsWith("/actuator/health");
        boolean annotated = handler instanceof HandlerMethod && ((HandlerMethod) handler).hasMethodAnnotation(AdminOnly.class);
        if (!administrativePath && !annotated) {
            return true;
        }
        boolean allowed = false;
        if (UserHolder.getUser() != null) {
            Long id = UserHolder.getUser().getId();
            boolean metrics = "/actuator/prometheus".equals(request.getRequestURI());
            boolean databaseAllowed = accessService != null
                    && (metrics ? accessService.canReadMetrics(id) : accessService.isAdmin(id));
            allowed = adminIds.contains(id) || databaseAllowed;
        }
        if (allowed) {
            return true;
        }
        boolean loggedIn = UserHolder.getUser() != null;
        response.setStatus(loggedIn ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.fail(
                loggedIn ? "FORBIDDEN" : "UNAUTHENTICATED", loggedIn ? "需要管理员权限" : "请先登录"));
        return false;
    }
}
