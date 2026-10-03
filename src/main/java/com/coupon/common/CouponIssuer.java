package com.coupon.common;

// 버전별 쿠폰 발급 서비스 공통 인터페이스
public interface CouponIssuer {

    int INITIAL_STOCK = 100;

    String version();

    String description();

    boolean publish();

    void reset();

    int getStock();

    int getIssuedCount();
}
