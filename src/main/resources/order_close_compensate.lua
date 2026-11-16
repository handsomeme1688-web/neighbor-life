-- Idempotently restore Redis stock after an unpaid order is canceled.
-- ARGV[1]: voucherId, ARGV[2]: orderId
local voucherId = ARGV[1]
local orderId = ARGV[2]
local markerKey = 'seckill:cancelled:' .. orderId
local stockKey = 'seckill:stock:' .. voucherId

-- Do not create a fake stock key when voucher cache initialization is missing.
local stock = redis.call('get', stockKey)
if (not stock or not tonumber(stock)) then
    return 2
end

if (redis.call('setnx', markerKey, '1') == 1) then
    redis.call('incrby', stockKey, 1)
    return 1
end
return 0
