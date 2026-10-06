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

// v5: v4 복사본 - DB 락 실험용 (JVM 락 없음)
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceV5 implements CouponIssuer {
    private final CouponStockRepository couponStockRepository;
    private final CouponPublishLogRepository couponPublishLogRepository;

    @Override
    public String version() {
        return "v5";
    }

    @Override
    public String description() {
        return "DB 락";
    }

    @Transactional
    @Override
    public boolean publish() {
        //재고 조회
        CouponStock couponStock = couponStockRepository.findByIdForUpdate(1L);
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
