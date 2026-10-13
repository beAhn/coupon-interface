package com.coupon.v5;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v5-3")
public class ConditionalUpdateCouponControllerV5 extends CouponController {

    public ConditionalUpdateCouponControllerV5(ConditionalUpdateCouponServiceV5 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
