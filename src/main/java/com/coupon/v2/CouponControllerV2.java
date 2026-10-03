package com.coupon.v2;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v2")
public class CouponControllerV2 extends CouponController {

    public CouponControllerV2(CouponServiceV2 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
