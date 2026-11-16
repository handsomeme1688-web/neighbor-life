-- Acknowledged records retained by older releases are already forwarded.
local latest = redis.call('xrevrange', KEYS[1], '+', '-', 'COUNT', 1)
if #latest == 0 then return 1 end
local pending = redis.call('xpending', KEYS[1], ARGV[1])
if pending[1] > 0 then return 0 end
local delivered = nil
for _, group in ipairs(redis.call('xinfo', 'GROUPS', KEYS[1])) do
    local name = nil
    local last = nil
    for i = 1, #group, 2 do
        if group[i] == 'name' then name = group[i + 1] end
        if group[i] == 'last-delivered-id' then last = group[i + 1] end
    end
    if name == ARGV[1] then delivered = last end
end
if not delivered then return 0 end
local function parts(id)
    local milliseconds, sequence = string.match(id, '^(%d+)%-(%d+)$')
    return tonumber(milliseconds), tonumber(sequence)
end
local latestTime, latestSequence = parts(latest[1][1])
local deliveredTime, deliveredSequence = parts(delivered)
if latestTime < deliveredTime or (latestTime == deliveredTime and latestSequence <= deliveredSequence) then
    return 1
end
return 0
