package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Guards against authentication state surviving servlet thread reuse.
 * @author NeighborLife
 */
class RefreshTokenInterceptorTest {
    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void clearsThreadContextBeforeAnonymousRequest() throws Exception {
        UserDTO previous = new UserDTO();
        previous.setId(1L);
        UserHolder.saveUser(previous);
        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(mock(StringRedisTemplate.class));
        interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());
        assertNull(UserHolder.getUser());
    }
}
