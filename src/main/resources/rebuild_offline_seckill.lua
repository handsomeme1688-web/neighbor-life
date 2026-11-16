-- Called only after draining an offline activity under the database inventory row lock.
redis.call('mset', KEYS[1], ARGV[1], KEYS[2], ARGV[2], KEYS[3], ARGV[3], KEYS[6], '0')
redis.call('del', KEYS[4], KEYS[5])
for i = 1, tonumber(ARGV[4]) do
    local offset = 4 + (i - 1) * 3
    local userId = ARGV[offset + 1]
    local orderId = ARGV[offset + 2]
    redis.call('sadd', KEYS[4], userId)
    redis.call('hset', KEYS[5], userId, orderId)
    if (ARGV[offset + 3] == '1') then
        redis.call('set', 'seckill:cancelled:' .. orderId, '1')
    end
end
return 1
