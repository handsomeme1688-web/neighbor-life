package com.hmdp.aspect;

import com.hmdp.annotation.SlidingWindowRateLimit;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.RateLimitExceededException;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Aspect
@Component
public class SlidingWindowRateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("sliding_window_rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private HttpServletRequest request;

    @Before("@annotation(com.hmdp.annotation.SlidingWindowRateLimit)")
    public void check(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        SlidingWindowRateLimit rule = signature.getMethod()
                .getAnnotation(SlidingWindowRateLimit.class);

        if (rule.windowSeconds() < 1) {
            throw new IllegalArgumentException("限流时间窗口必须大于0");
        }

        List<String> keys = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        String prefix = "rate-limit:" + rule.key() + ":";
        addDimension(keys, limits, prefix + "global", rule.globalLimit());
        addDimension(keys, limits, prefix + "ip:" + request.getRemoteAddr(), rule.ipLimit());

        UserDTO user = UserHolder.getUser();
        if (user != null) {
            addDimension(keys, limits, prefix + "user:" + user.getId(), rule.userLimit());
        }
        if (keys.isEmpty()) {
            return;
        }

        List<String> args = new ArrayList<>();
        args.add(Integer.toString(rule.windowSeconds() * 1000));
        args.add(UUID.randomUUID().toString());
        args.addAll(limits);
        Long rejectedDimension = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT, keys, args.toArray(new String[0]));
        if (rejectedDimension == null) {
            throw new RateLimitExceededException("限流服务暂不可用，请稍后重试");
        }
        if (rejectedDimension > 0) {
            throw new RateLimitExceededException("请求过于频繁，请稍后重试");
        }
    }

    private void addDimension(List<String> keys, List<String> limits, String key, int limit) {
        if (limit > 0) {
            keys.add(key);
            limits.add(Integer.toString(limit));
        }
    }
}
