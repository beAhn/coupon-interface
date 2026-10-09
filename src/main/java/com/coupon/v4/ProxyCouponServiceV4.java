package com.coupon.v4;

import com.coupon.common.CouponIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProxyCouponServiceV4 implements CouponIssuer {
    private final CouponServiceV4 couponServiceV4;

    @Override
    public String version() {
        return couponServiceV4.version();
    }

    @Override
    public String description() {
        return "DB연동(JPA / Synchronized + 프록시 패턴)";
    }

    //@Transactional이 걸린 publish()에 직접 synchronized 동시성 처리를 하면 락이 풀린 이후 업데이트가 되기 때문에 락과 업데이트 시점에 동시성 제어가 안 됨 -> 프록시로 처리
    @Override
    public synchronized boolean publish() {
        boolean isSuccessPublish = couponServiceV4.publish();
        return isSuccessPublish;
    }

    @Override
    public void reset() {
        couponServiceV4.reset();
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
