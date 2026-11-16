package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.security.AccessService;
import com.hmdp.utils.RedisConstants;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Protects Actuator's separate handler mapping before MVC interceptors run.
 * @author NeighborLife
 */
@Component
@Order(20)
public class ActuatorAuthorizationFilter extends OncePerRequestFilter {
    private static final String METRICS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private AccessService access;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/actuator") || "/actuator/health".equals(path) || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith(BEARER_PREFIX)) { token = token.substring(BEARER_PREFIX.length()); }
        Object identity = token == null || token.length() > 128 ? null
                : redis.opsForHash().get(RedisConstants.LOGIN_USER_KEY + token, "id");
        Long user = null;
        if (identity != null) {
            try { user = Long.valueOf(identity.toString()); }
            catch (NumberFormatException exception) { user = null; }
        }
        boolean authorized = user != null && (METRICS_PATH.equals(request.getServletPath())
                ? access.canReadMetrics(user) : access.isAdmin(user));
        if (authorized) { chain.doFilter(request, response); return; }
        response.setStatus(user == null ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.fail(user == null ? "UNAUTHENTICATED" : "FORBIDDEN",
                user == null ? "请先登录" : "需要监控或管理员权限"));
    }
}
