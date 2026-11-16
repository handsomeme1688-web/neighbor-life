-- Flash-sale admission script.
-- ARGV[1]: voucherId, ARGV[2]: userId, ARGV[3]: orderId
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local streamKey = 'stream.orders'
local reservationKey = 'seckill:reservation:' .. voucherId

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local beginKey = 'seckill:begin:' .. voucherId
local endKey = 'seckill:end:' .. voucherId

if (redis.call('get', 'seckill:enabled:' .. voucherId) == '0') then return 6 end
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

-- Redis scripts serialize commands; preflight key types before the first write.
local streamType = redis.call('type', streamKey).ok
local reservationType = redis.call('type', reservationKey).ok
if ((streamType ~= 'none' and streamType ~= 'stream')
        or (reservationType ~= 'none' and reservationType ~= 'hash')) then
    return 5
end

local requestKey = 'seckill:request:' .. orderId
if (redis.call('exists', requestKey) == 1) then return 2 end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', reservationKey, userId, orderId)
redis.call('hset', 'seckill:request:' .. orderId, 'userId', userId, 'voucherId', voucherId)
redis.call('expire', 'seckill:request:' .. orderId, 86400)
redis.call('xadd', streamKey, '*',
        'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
