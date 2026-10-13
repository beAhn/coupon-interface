package com.coupon.v5;

import com.coupon.common.CouponIssuer;
import com.coupon.domain.entity.CouponPublishLog;
import com.coupon.domain.entity.CouponStock;
import com.coupon.domain.repository.CouponPublishLogRepository;
import com.coupon.domain.repository.CouponStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConditionalUpdateCouponServiceV5 implements CouponIssuer {
    private final CouponStockRepository couponStockRepository;
    private final CouponPublishLogRepository couponPublishLogRepository;

    @Override
    public String version() {
        return "v5-3";
    }

    @Override
    public String description() {
        return "DB 조건부 업데이트";
    }

    @Transactional
    @Override
    public boolean publish() {
        //조건부 업데이트로 조회를 따로하지 않음. row단위 X락이 걸려서 동시성 제어가 된다.
        int successCnt = couponStockRepository.updateCouponStockByConditionalUpdate(1L);

        if (successCnt > 0) {
            CouponStock referenceCouponStockById = couponStockRepository.getReferenceById(1L);

            couponPublishLogRepository.save(new CouponPublishLog(referenceCouponStockById));

            return true;
        } else {
            return false;
        }
    }

    @Override
    @Transactional
    public void reset() {
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
