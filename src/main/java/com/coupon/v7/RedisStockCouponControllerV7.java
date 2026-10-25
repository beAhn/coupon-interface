package com.coupon.v7;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/v7-1")
@Controller
public class RedisStockCouponControllerV7 extends CouponController {
    protected RedisStockCouponControllerV7(RedisStockCouponServiceV7 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
