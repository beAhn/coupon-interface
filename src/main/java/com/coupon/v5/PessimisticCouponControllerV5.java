package com.coupon.v5;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v5-1")
public class PessimisticCouponControllerV5 extends CouponController {

    public PessimisticCouponControllerV5(PessimisticCouponServiceV5 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
