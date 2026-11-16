redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('del', KEYS[2])
return 1
