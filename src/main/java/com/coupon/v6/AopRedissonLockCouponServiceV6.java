package com.coupon.v6;

import com.coupon.common.CouponIssuer;
import com.coupon.v4.CouponServiceV4;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// v6-3: Redisson 분산락을 AOP 설정 변경
@Slf4j
@Service
@RequiredArgsConstructor
public class AopRedissonLockCouponServiceV6 implements CouponIssuer {

    private final CouponServiceV4 couponServiceV4;
    private final AopRedissonLockPublisherV6 lockPublisher;

    @Override
    public String version() {
        return "v6-3";
    }

    @Override
    public String description() {
        return "DB연동(JPA / Redission AOP 분산 락)";
    }

    @Override
    public boolean publish() {
        // 품절 빠른 반환: 락 밖 확인은 거절에만 사용 (승인은 락 안 decrease()에서). 재고는 줄기만 하므로 0을 믿어도 됨
        if (couponServiceV4.getStock() <= 0) {
            return false;
        }

        return lockPublisher.publish();
    }

    @Override
    public void reset() {
        lockPublisher.reset();
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
