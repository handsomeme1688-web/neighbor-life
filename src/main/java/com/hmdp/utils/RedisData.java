package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime; // 逻辑过期时间
    private Object data; // RedisData自己带有逻辑过期时间，并且它里面带有数据
                         // 万能的存储数据的对象，不需要对原来的实体做任何的修改
}
