-- v7-1 보상: INSERT 실패 시 pending에 있을 때만 ZREM + INCR (중복 보상 방지)
-- KEYS = [stock, pending], ARGV = [requestId]
-- 반환: 복구 후 재고 / -1 이미 처리됨

if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then
    return redis.call('INCR', KEYS[1])
end

return -1
