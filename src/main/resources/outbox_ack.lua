-- The order relay is the sole consumer group of this outbox.
-- KEYS[1]: stream, ARGV[1]: group, ARGV[2]: record id
local acknowledged = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
if (acknowledged == 1) then
    redis.call('xdel', KEYS[1], ARGV[2])
end
return acknowledged
