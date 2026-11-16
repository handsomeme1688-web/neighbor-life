package com.hmdp.config;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Propagates a bounded request identifier and releases servlet-thread context.
 * @author NeighborLife
 */
@Component
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final String TRACE_ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String trace = request.getHeader("X-Request-Id");
        if (trace == null || !trace.matches(TRACE_ID_PATTERN)) {
            trace = UUID.randomUUID().toString();
        }
        MDC.put("traceId", trace);
        response.setHeader("X-Request-Id", trace);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
