package com.coupon.v8;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.coupon.v7.CouponRedisKeys.ISSUED_KEY;
import static com.coupon.v7.CouponRedisKeys.PENDING_KEY;
import static com.coupon.v7.CouponRedisKeys.STOCK_KEY;

// v8-3: 재시도 후에도 실패한 메시지 로그 기록 + Redis 승인 취소 (재고·issued 복구, pending 정리)
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueDltConsumerV8 {
    private final StringRedisTemplate redisTemplate;

    // v7-2 보상 스크립트 재사용 (pending에 있을 때만 복구 → 중복 수신해도 한 번만)
    private static final RedisScript<Long> ROLLBACK_SCRIPT = RedisScript.of(new ClassPathResource("scripts/v7-2/rollback.lua"), Long.class);

    @KafkaListener(topics = KafkaCouponServiceV8.TOPIC_DLT, groupId = "coupon-issue-dlt-consumer", concurrency = "3")
    public void consume(CouponIssueMessage message, @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String reason) {
        log.warn("DLT 처리-userId: {}, reason: {}", message.userId(), reason);

        Long result = redisTemplate.execute(ROLLBACK_SCRIPT, List.of(STOCK_KEY, PENDING_KEY, ISSUED_KEY), message.userId());

        if (result != null && result >= 0) {
            log.warn("DLT 복구-userId: {}, 재고: {}", message.userId(), result);
        } else {
            log.info("DLT 이미 복구됨-userId: {}", message.userId());
        }
    }
}
