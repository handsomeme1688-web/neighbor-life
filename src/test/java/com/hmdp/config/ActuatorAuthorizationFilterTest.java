package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.security.AccessService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies authorization at the filter boundary used by Actuator handlers.
 * @author NeighborLife
 */
class ActuatorAuthorizationFilterTest {
    @Test
    void requiresIdentityAndMetricsRoleWhileKeepingHealthPublic() throws Exception {
        ActuatorAuthorizationFilter filter = new ActuatorAuthorizationFilter();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String,Object,Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.get("login:token:session", "id")).thenReturn("10");
        AccessService access = mock(AccessService.class);
        ReflectionTestUtils.setField(filter, "redis", redis);
        ReflectionTestUtils.setField(filter, "access", access);
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setServletPath("/actuator/prometheus");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        filter.doFilter(request, anonymous, chain);
        assertEquals(401, anonymous.getStatus());
        request.addHeader("Authorization", "session");
        MockHttpServletResponse ordinary = new MockHttpServletResponse();
        filter.doFilter(request, ordinary, chain);
        assertEquals(403, ordinary.getStatus());
        when(access.canReadMetrics(10L)).thenReturn(true);
        MockHttpServletResponse observer = new MockHttpServletResponse();
        filter.doFilter(request, observer, chain);
        verify(chain).doFilter(request, observer);
        request.removeHeader("Authorization");
        request.addHeader("Authorization", "Bearer session");
        MockHttpServletResponse scraper = new MockHttpServletResponse();
        filter.doFilter(request, scraper, chain);
        verify(chain).doFilter(request, scraper);
        request.setServletPath("/actuator/health/readiness");
        MockHttpServletResponse health = new MockHttpServletResponse();
        filter.doFilter(request, health, chain);
        verify(chain).doFilter(request, health);
    }
}
