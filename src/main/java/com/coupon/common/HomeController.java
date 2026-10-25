package com.coupon.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 버전 목록(issuers, gnbGroups)은 GnbAdvice가 model에 넣음
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "home";
    }
}
