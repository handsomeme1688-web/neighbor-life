package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import javax.servlet.ServletException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies request context survives processing and is always released.
 * @author NeighborLife
 */
class RequestTraceFilterTest {
    @Test
    void clearsTraceWhenRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id","trace-test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> new RequestTraceFilter().doFilter(request,response,(in,out) -> {
            assertEquals("trace-test",MDC.get("traceId"));
            throw new ServletException("request failed");
        }));
        assertNull(MDC.get("traceId"));
        assertEquals("trace-test",response.getHeader("X-Request-Id"));
    }
}
