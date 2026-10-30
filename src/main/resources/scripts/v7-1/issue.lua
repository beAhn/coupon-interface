-- v7-1 발급: 재고 확인 + DECR + ZADD를 한 번에 실행 (음수로 내려가지 않음)
-- KEYS = [stock, pending], ARGV = [requestId, timestamp]
-- 반환: 남은 재고(>= 0) / -1 품절

local stock = tonumber(redis.call('GET', KEYS[1]))   -- 키 없으면 GET은 false → tonumber로 nil
if stock == nil or stock <= 0 then
    return -1
end

local remain = redis.call('DECR', KEYS[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])

return remain
