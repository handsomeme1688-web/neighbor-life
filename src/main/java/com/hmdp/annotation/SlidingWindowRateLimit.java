package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SlidingWindowRateLimit {

    String key();

    int windowSeconds() default 1;

    int globalLimit() default 0;

    int ipLimit() default 0;

    int userLimit() default 0;
}
