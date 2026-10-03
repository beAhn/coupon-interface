package com.coupon.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Comparator;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final List<CouponIssuer> issuers;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("issuers", issuers.stream()
                .sorted(Comparator.comparing(CouponIssuer::version))
                .toList());
        return "home";
    }
}
