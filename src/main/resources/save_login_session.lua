-- ARGV[1]: TTL in seconds; remaining arguments are hash field/value pairs.
redis.call('hset', KEYS[1], unpack(ARGV, 2))
redis.call('expire', KEYS[1], ARGV[1])
return 1
