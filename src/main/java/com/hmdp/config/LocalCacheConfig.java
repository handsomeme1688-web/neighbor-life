package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * LocalCacheConfig component.
 *
 * @author NeighborLife
 */
@Configuration
public class LocalCacheConfig {
    @Bean
    public Cache<Long, com.hmdp.dto.VoucherDetailDTO> voucherLocalCache() {
        return Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(60L, TimeUnit.SECONDS).recordStats().build();
    }


    @Bean
    public Cache<Long, Shop> shopLocalCache(
            @Value("${app.cache.shop-local-max-size:1000}") long maximumSize,
            @Value("${app.cache.shop-local-ttl-seconds:60}") long ttlSeconds) {
        return Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
