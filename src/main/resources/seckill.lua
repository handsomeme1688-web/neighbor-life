-- Flash-sale admission script.
-- ARGV[1]: voucherId, ARGV[2]: userId, ARGV[3]: orderId
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local beginKey = 'seckill:begin:' .. voucherId
local endKey = 'seckill:end:' .. voucherId

local stock = redis.call('get', stockKey)
local beginTime = redis.call('get', beginKey)
local endTime = redis.call('get', endKey)
if (not stock or not beginTime or not endTime) then
    return 5
end

-- Redis TIME is the common clock for every application instance.
local now = tonumber(redis.call('time')[1])
if (now < tonumber(beginTime)) then
    return 3
end
if (now > tonumber(endTime)) then
    return 4
end
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
if (tonumber(stock) <= 0) then
    return 1
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*',
        'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
