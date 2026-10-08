package com.coupon.v5;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v5-2")
public class OptimisticCouponControllerV5 extends CouponController {

    public OptimisticCouponControllerV5(OptimisticCouponServiceV5 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
