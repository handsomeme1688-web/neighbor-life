package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.lang.model.SourceVersion;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker { // 基于redis的id生成器
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1767225600;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        // 生成策略是基于redis的自增长,需要有一个key,对应的值不断自增,不同的业务要有不同的key
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2. 生成序列号
        // 利用redis自增长                         // redis单个key的自增长对应的值有一个上限,2的64次方
        // 2.1 获取当前日期,精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date, 1);
        // 用基本类型,不要用包装类Long,等一会还要运算

        // 3. 拼接返回
        // 借助位运算，时间戳现在在低位，让他把低位让出来，往前挪
        return timestamp << COUNT_BITS | count; // 用或运算
    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("second = " + second);
//    }
}
