package com.coupon.common;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 모든 화면 GNB용 버전 목록 (k6 API 요청에도 실행되므로 정렬된 리스트 반환만)
@ControllerAdvice
public class GnbAdvice {

    private final List<CouponIssuer> issuers;
    private final Map<String, List<CouponIssuer>> gnbGroups;

    // 기동 시 1번: 버전순 정렬 + 같은 version 중복 제거(v4 Proxy) + 큰 버전(v7)별 묶음
    public GnbAdvice(List<CouponIssuer> issuers) {
        Map<String, CouponIssuer> byVersion = new TreeMap<>();
        issuers.forEach(issuer -> byVersion.putIfAbsent(issuer.version(), issuer));
        this.issuers = List.copyOf(byVersion.values());

        this.gnbGroups = this.issuers.stream()
                .collect(Collectors.groupingBy(issuer -> issuer.version().split("-")[0],
                        LinkedHashMap::new, Collectors.toList()));
    }

    @ModelAttribute("issuers")
    public List<CouponIssuer> issuers() {
        return issuers;
    }

    // {v5=[v5-1, v5-2, v5-3], v6=[v6, v6-2, v6-3], ...}
    @ModelAttribute("gnbGroups")
    public Map<String, List<CouponIssuer>> gnbGroups() {
        return gnbGroups;
    }
}
