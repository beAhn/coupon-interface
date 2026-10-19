package com.coupon.v6;

import com.coupon.aop.DistributedLock;
import com.coupon.v4.CouponServiceV4;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// v6-3: Redisson 분산락을 AOP 설정 변경
@Slf4j
@Service
@RequiredArgsConstructor
public class AopRedissonLockPublisherV6 {
    private static final String LOCK_KEY = "lock:coupon:1:aop";

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;

    @DistributedLock(key = LOCK_KEY)
    public boolean publish() {
        return couponServiceV4.publish();
    }

    public void reset() {
        couponServiceV4.reset();
        redisTemplate.delete(LOCK_KEY);
    }
}
