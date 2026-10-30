-- v7-2 보상: INSERT 실패 시 pending에 있을 때만 재고 복구 + issued에서 제거 (재요청 허용)
-- KEYS = [stock, pending, issued], ARGV = [userId]
-- 반환: 복구 후 재고 / -1 이미 처리됨

if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('SREM', KEYS[3], ARGV[1])
    return redis.call('INCR', KEYS[1])
end

return -1
