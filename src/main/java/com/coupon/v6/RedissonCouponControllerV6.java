package com.coupon.v6;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/v6-2")
@Controller
public class RedissonCouponControllerV6 extends CouponController {
    protected RedissonCouponControllerV6(RedissonLockCouponServiceV6 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
