package com.coupon.v8;

import com.coupon.common.BulkIssueSimulator;
import com.coupon.common.CouponController;
import com.coupon.v7.IssueResult;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.concurrent.ThreadLocalRandom;

@RequestMapping("/v8-1")
@Controller
public class KafkaCouponControllerV8 extends CouponController {
    private final KafkaCouponServiceV8 couponService;

    private static final int DISTRIBUTED_USERS = 150;   // 분산 대량 발급 회원 수 (재고 100보다 많게)

    @Value("${SERVER_NAME:local}")
    private String serverName;

    protected KafkaCouponControllerV8(KafkaCouponServiceV8 couponService, BulkIssueSimulator bulkIssueSimulator) {
        super(couponService, bulkIssueSimulator);
        this.couponService = couponService;
    }

    @Override
    protected String distributedIssuePath() {
        return "/api/issue-user?userId=user-" + ThreadLocalRandom.current().nextInt(1, DISTRIBUTED_USERS + 1);
    }

    // 200 발급 확정(DB 반영 대기) / 409 품절·중복 / 500 오류
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
