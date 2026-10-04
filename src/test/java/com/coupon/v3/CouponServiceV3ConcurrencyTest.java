package com.coupon.v3;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponIssuer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CouponServiceV3ConcurrencyTest {

    @Test
    void 동시에_1만건_발급_요청() throws InterruptedException {
        CouponServiceV3 couponService = new CouponServiceV3();

        BulkIssueSimulator.Result result = new BulkIssueSimulator().run(couponService);

        System.out.printf("성공: %d, 남은 재고: %d, 발급 수: %d%n",
                result.successCount(), couponService.getStock(), couponService.getIssuedCount());

        assertEquals(CouponIssuer.INITIAL_STOCK, result.successCount());
        assertEquals(0, couponService.getStock());
        assertEquals(CouponIssuer.INITIAL_STOCK, couponService.getIssuedCount());
    }
}
