-- KEYS: stock, begin, end. ARGV: initial stock, begin second, end second.
local exists = redis.call('exists', KEYS[1], KEYS[2], KEYS[3])
if (exists == 3) then
    for i = 1, 3 do
        if (redis.call('type', KEYS[i]).ok ~= 'string' or not tonumber(redis.call('get', KEYS[i]))) then
            return 2
        end
    end
    return 0
end
if (exists ~= 0) then
    return 2
end
redis.call('mset', KEYS[1], ARGV[1], KEYS[2], ARGV[2], KEYS[3], ARGV[3])
return 1
