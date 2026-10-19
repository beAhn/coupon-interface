package com.coupon.v6;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/v6")
@Controller
public class RedisCouponControllerV6 extends CouponController {
    protected RedisCouponControllerV6(RedisLockCouponServiceV6 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
