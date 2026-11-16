-- ARGV[1]: voucherId, ARGV[2]: userId, ARGV[3]: orderId
local voucherId = ARGV[1]
local userId = ARGV[2]
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local reservationKey = 'seckill:reservation:' .. voucherId
local reservation = redis.call('hget', reservationKey, userId)

-- A delayed duplicate must preserve a subsequent reservation by the same user.
if (reservation and reservation ~= ARGV[3]) then
    return 0
end
if (redis.call('sismember', orderKey, userId) == 0) then
    return 0
end
local stock = redis.call('get', stockKey)
if (not reservation or not stock or not tonumber(stock)) then
    return 2
end

-- SREM makes compensation idempotent: restore stock at most once.
if (redis.call('srem', orderKey, userId) == 1) then
    redis.call('hdel', reservationKey, userId)
    redis.call('incrby', stockKey, 1)
    return 1
end
return 0
