package com.coupon.v6;

import com.coupon.common.CouponIssuer;
import com.coupon.v4.CouponServiceV4;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

// v6: v4-2의 synchronized 자리를 Redis 분산 락으로 교체 (트랜잭션 서비스는 v4 그대로)
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockCouponServiceV6 implements CouponIssuer {
    private static final String LOCK_KEY = "lock:coupon:1";
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);   // 보유자가 죽어도 3초 뒤 자동 해제
    private static final long WAIT_MILLIS = 60_000;                    // 비교 실험용: 대기 시간 초과로 실패하는 요청이 없도록 넉넉히
    private static final long RETRY_INTERVAL_MILLIS = 10;

    // 값이 내 토큰일 때만 삭제 (GET + DEL을 한 번에 실행)
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('DEL', KEYS[1]) " +
            "end " +
            "return 0",
            Long.class);

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;

    @Override
    public String version() {
        return "v6";
    }

    @Override
    public String description() {
        return "DB연동(JPA / Redis 분산 락 + 프록시 패턴)";
    }

    // 락 획득 → 트랜잭션(커밋까지) → 락 해제 순서라 커밋 전 해제 문제 없음
    @Override
    public boolean publish() {
        // 품절 빠른 반환: 락 밖 확인은 거절에만 사용 (승인은 락 안 decrease()에서). 재고는 줄기만 하므로 0을 믿어도 됨
        if (couponServiceV4.getStock() <= 0) {
            return false;
        }

        String token = UUID.randomUUID().toString();   // 요청마다 새로 생성 (필드면 모든 스레드가 같은 토큰)
        if (!acquire(token)) {
            log.warn("락 대기 시간 초과");
            return false;
        }
        try {
            return couponServiceV4.publish();
        } finally {
            release(token);
        }
    }

    // SET lock:coupon:1 <token> NX PX 3000, 실패하면 잠깐 쉬고 재시도 (스핀 락)
    private boolean acquire(String token) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;

        //1분 동안 락이 해제되는지 체크 후 처리
        while (System.currentTimeMillis() < deadline) {
            //SET NX(키가 있으면 false 반환)
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);

            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void release(String token) {
        Long deleted = redisTemplate.execute(RELEASE_SCRIPT, List.of(LOCK_KEY), token);
        if (deleted == null || deleted == 0) {
            log.warn("내 락이 아님 (TTL 만료 후 다른 요청이 획득)");
        }
    }

    @Override
    public void reset() {
        couponServiceV4.reset();
        redisTemplate.delete(LOCK_KEY);   // 이전 실험의 남은 락 정리
    }

    @Override
    public int getStock() {
        return couponServiceV4.getStock();
    }

    @Override
    public int getIssuedCount() {
        return couponServiceV4.getIssuedCount();
    }
}
