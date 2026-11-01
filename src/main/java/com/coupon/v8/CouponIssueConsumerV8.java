package com.coupon.v8;

import com.coupon.domain.entity.CouponPublishLog;
import com.coupon.domain.entity.CouponStock;
import com.coupon.domain.repository.CouponPublishLogRepository;
import com.coupon.domain.repository.CouponStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.coupon.v7.CouponRedisKeys.PENDING_KEY;

// v8-1: 토픽에서 발급 메시지를 받아 DB 반영 (app1·app2가 같은 컨슈머 그룹 → 파티션 나눠 받음)
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueConsumerV8 {

    private final CouponPublishLogRepository couponPublishLogRepository;
    private final CouponStockRepository couponStockRepository;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = KafkaCouponServiceV8.TOPIC, groupId = "coupon-issue-consumer", concurrency = "2")
    public void consume(CouponIssueMessage message) {
        try {
            CouponStock couponStock = couponStockRepository.getReferenceById(1L);
            couponPublishLogRepository.save(new CouponPublishLog(couponStock, message.userId()));

        } catch(DataIntegrityViolationException e) {
            log.info("이미 처리된 발급 userId={}", message.userId());
        }

        redisTemplate.opsForZSet().remove(PENDING_KEY, message.userId());

    }
}
