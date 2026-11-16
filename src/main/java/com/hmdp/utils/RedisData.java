package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RedisData component.
 *
 * @author NeighborLife
 */
@Data
public class RedisData {
    /**
     * 逻辑过期时间
     */
    private LocalDateTime expireTime;
    /**
     * RedisData自己带有逻辑过期时间，并且它里面带有数据
     */
    private Object data;
                         // 万能的存储数据的对象，不需要对原来的实体做任何的修改
}
