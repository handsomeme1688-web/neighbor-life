local attemptsKey = KEYS[2] or (KEYS[1] .. ':attempts')
local code = redis.call('get', KEYS[1])
if (not code) then return 0 end
if (code == ARGV[1]) then
    redis.call('del', KEYS[1], attemptsKey)
    return 1
end
local attempts = redis.call('incr', attemptsKey)
redis.call('expire', attemptsKey, 120)
if (attempts >= 5) then redis.call('del', KEYS[1]) end
return 0
