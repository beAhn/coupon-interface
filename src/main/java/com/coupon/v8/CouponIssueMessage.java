package com.coupon.v8;

// v8-1: Redis 승인 후 Kafka로 보내는 발급 메시지 (컨슈머가 DB 반영)
// TODO: 필드 확정 (userId 외에 couponId·요청 시각이 필요한지)
public record CouponIssueMessage(String userId, long requestedAt) {
}
