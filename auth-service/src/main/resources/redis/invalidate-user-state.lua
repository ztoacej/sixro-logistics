-- Persist the event marker with the deletion, so retries cannot delete a new login.
-- No TTL: deduplication must survive delayed Outbox/Kafka replay.
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 0
end
redis.call('DEL', KEYS[1], KEYS[2])
redis.call('SET', KEYS[3], ARGV[1])
return 1
