package com.coupon.v6;

import com.coupon.common.CouponIssuer;
import com.coupon.v4.CouponServiceV4;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

// v6-2: v6 직접 구현 락을 Redisson RLock으로 교체(pub/sub 대기, watchdog)
@Slf4j
@Service
@RequiredArgsConstructor
public class RedissonLockCouponServiceV6 implements CouponIssuer {
    private static final String LOCK_KEY = "lock:coupon:1:redisson";

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    @Override
    public String version() {
        return "v6-2";
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

        //레디슨 락 사용
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;

        try {
            acquired = lock.tryLock(60, TimeUnit.SECONDS); //대기 60초, leaseTime 생략

            if (!acquired) {
                return false;
            }

            return couponServiceV4.publish();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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
