package com.coupon.v4;

import com.coupon.common.CouponIssuer;
import com.coupon.v4.entity.CouponPublishLog;
import com.coupon.v4.entity.CouponStock;
import com.coupon.v4.repository.CouponPublishLogRepository;
import com.coupon.v4.repository.CouponStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.atomic.AtomicInteger;

// v4: v3 복사본 - 멀티 서버(DB 없음) 실험용
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceV4 implements CouponIssuer {
    private final CouponStockRepository couponStockRepository;
    private final CouponPublishLogRepository couponPublishLogRepository;

    @Override
    public String version() {
        return "v4";
    }

    @Override
    public String description() {
        return "DB연동(JPA)";
    }

    @Transactional
    @Override
    public boolean publish() {
        //재고 조회
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
