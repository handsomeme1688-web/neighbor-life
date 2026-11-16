package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies administrator and public-read access boundaries.
 * @author NeighborLife
 */
class AdminInterceptorTest {
    private final AdminInterceptor interceptor = new AdminInterceptor("1", new ObjectMapper());

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void rejectsAnonymousAdministration() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, writeHandler()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNAUTHENTICATED"));
    }

    @Test
    void rejectsOrdinaryUserAdministration() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, writeHandler()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void acceptsConfiguredAdministrator() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), writeHandler()));
    }

    @Test
    void permitsAnonymousPublicRead() throws Exception {
        HandlerMethod handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
    }

    private HandlerMethod writeHandler() throws NoSuchMethodException {
        return new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("write"));
    }

    /** Test endpoint metadata. */
    public static class Endpoint {
        @AdminOnly
        public void write() {
        }

        public void read() {
        }
    }
}
