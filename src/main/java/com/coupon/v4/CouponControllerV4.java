package com.coupon.v4;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v4")
public class CouponControllerV4 extends CouponController {

    //v4-1
//    public CouponControllerV4(CouponServiceV4 couponService, BulkIssueSimulator bulkIssueSimulator) {
//        super(couponService, bulkIssueSimulator);
//    }

    //v4-2: 프록시 패턴을 적용하여, synchronized가 정상 동작하도록 처리
    public CouponControllerV4(ProxyCouponServiceV4 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
    }
}
