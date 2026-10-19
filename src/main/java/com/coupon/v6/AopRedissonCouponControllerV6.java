package com.coupon.v6;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/v6-3")
@Controller
public class AopRedissonCouponControllerV6 extends CouponController {
    protected AopRedissonCouponControllerV6(AopRedissonLockCouponServiceV6 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
