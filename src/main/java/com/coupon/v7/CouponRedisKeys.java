package com.coupon.v7;

// v7 쿠폰 Redis 키 (끝의 :1 = 쿠폰 id)
public final class CouponRedisKeys {
    public static final String STOCK_KEY = "coupon:stock:1";       // 재고 (v7-1·v7-2 공유)
    public static final String PENDING_KEY = "coupon:pending:1";   // Redis 승인 후 DB 미반영 요청 (v7-1·v7-2 공유)
    public static final String ISSUED_KEY = "coupon:issued:1";     // 발급받은 userId, 1인 1매 (v7-2)

    private CouponRedisKeys() {
    }
}
