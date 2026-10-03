package com.coupon.v1;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v1")
public class CouponControllerV1 extends CouponController {

    public CouponControllerV1(CouponServiceV1 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
