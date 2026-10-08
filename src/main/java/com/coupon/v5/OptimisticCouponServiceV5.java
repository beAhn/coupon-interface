package com.coupon.v5;

import com.coupon.common.CouponIssuer;
import com.coupon.domain.entity.CouponPublishLog;
import com.coupon.domain.entity.CouponStock;
import com.coupon.domain.repository.CouponPublishLogRepository;
import com.coupon.domain.repository.CouponStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// v5-2: 낙관적 락 (@Version)
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimisticCouponServiceV5 implements CouponIssuer {
    private final CouponStockRepository couponStockRepository;
    private final CouponPublishLogRepository couponPublishLogRepository;

    @Override
    public String version() {
        return "v5-2";
    }

    @Override
    public String description() {
        return "DB 낙관적 락";
    }

    @Transactional
    @Override
    public boolean publish() {
        //재고 조회
        //낙관적 락(v5-2)인 상태에서는 version값을 가져옴
        CouponStock couponStock = couponStockRepository.findById(1L).orElseThrow();
        boolean isDecreaseSuccess = couponStock.decrease();

        if (isDecreaseSuccess) {
            CouponPublishLog couponPublishLog = new CouponPublishLog(couponStock);
            //발급
            couponPublishLogRepository.save(couponPublishLog);

            return true;
        }

        return false;
    }

    @Transactional
    @Override
    public void reset() {
        //재고 초기화
        CouponStock couponStock = couponStockRepository.findById(1L).orElseThrow();
        couponStock.reset();

        //발급초기화
        couponPublishLogRepository.deleteAllInBatch();
    }

    @Override
    public int getStock() {
        return couponStockRepository.findById(1L).orElseThrow().getStockQuantity();
    }

    @Override
    public int getIssuedCount() {
        long count = couponPublishLogRepository.count();
        return Math.toIntExact(count);
    }
}
