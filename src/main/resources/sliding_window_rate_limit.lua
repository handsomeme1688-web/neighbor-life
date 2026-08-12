-- KEYS: rate-limit dimensions
-- ARGV[1]: window milliseconds, ARGV[2]: unique request id
-- ARGV[3...]: limits matching KEYS
local redisTime = redis.call('time')
local now = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
local window = tonumber(ARGV[1])

for index, key in ipairs(KEYS) do
    redis.call('zremrangebyscore', key, 0, now - window)
    local count = redis.call('zcard', key)
    local limit = tonumber(ARGV[index + 2])
    if (count >= limit) then
        return index
    end
end

for _, key in ipairs(KEYS) do
    redis.call('zadd', key, now, ARGV[2])
    redis.call('pexpire', key, window)
end
return 0
