package com.coupon.v7;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@RequestMapping("/v7-2")
@Controller
public class OnePerUserCouponControllerV7 extends CouponController {
    private final OnePerUserCouponServiceV7 couponService;

    @Value("${SERVER_NAME:local}")
    private String serverName;

    protected OnePerUserCouponControllerV7(OnePerUserCouponServiceV7 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
        this.couponService = couponService;
    }

    // 1인 1매 발급 API: 200 성공 / 409 품절·중복(message로 구분) / 500 오류
    @PostMapping("/api/issue-user")
    @ResponseBody
    public IssueResponse publishByUser(@RequestParam String userId, HttpServletResponse response) {
        IssueResult result = couponService.publish(userId);

        response.setStatus(switch (result) {
            case SUCCESS -> HttpServletResponse.SC_OK;
            case SOLD_OUT, DUPLICATE -> HttpServletResponse.SC_CONFLICT;
            case ERROR -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        });

        return new IssueResponse(result == IssueResult.SUCCESS, serverName, result.name());
    }
}
