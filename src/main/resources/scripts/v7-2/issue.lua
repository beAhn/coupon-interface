-- v7-2 발급: 확인(재고) → 확인+등록(SADD) → 변경(DECR, ZADD). 거절은 상태 변경 전에만
-- KEYS = [stock, pending, issued], ARGV = [userId, timestamp]
-- 반환: 남은 재고(>= 0) / -1 품절 / -2 중복

local stock = tonumber(redis.call('GET', KEYS[1]))   -- 키 없으면 GET은 false → tonumber로 nil
if stock == nil or stock <= 0 then
    return -1
end

if redis.call('SADD', KEYS[3], ARGV[1]) == 0 then   -- 0 = 이미 받은 userId
    return -2
end


local remain = redis.call('DECR', KEYS[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])       -- pending member도 userId

return remain
