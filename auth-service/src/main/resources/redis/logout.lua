-- Compare and delete within the same atomic operation. A new login or token
-- rotation after application-level validation must not be deleted by old logout.
local currentHash = redis.call('GET', KEYS[1])
local currentSession = redis.call('GET', KEYS[2])

if currentHash == false or currentSession == false
        or currentHash ~= ARGV[1] or currentSession ~= ARGV[2] then
    return 0
end

local ttl = tonumber(ARGV[4])

redis.call('DEL', KEYS[1])
redis.call('DEL', KEYS[2])

if ARGV[3] ~= '' and ttl ~= nil and ttl > 0 then
    redis.call('SET', KEYS[3], 'logout', 'PX', ttl)
end

return 1
