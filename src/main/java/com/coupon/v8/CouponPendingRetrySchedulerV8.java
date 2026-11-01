package com.coupon.v8;


import com.coupon.v7.CouponRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@EnableScheduling
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponPendingRetrySchedulerV8 {
    private final KafkaTemplate<String, CouponIssueMessage> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "coupon:pending-retry:lock";

    //애플리케이션 1대에서만 스케줄러 돌도록 처리
    @Scheduled(fixedDelay = 5000)
    public void retrySend() {
        RLock lock = redissonClient.getLock(LOCK_KEY);

        // 대기 없이 시도: 다른 서버가 실행 중이면 이번 주기는 건너뜀
        if (!lock.tryLock()) {
            return;
        }

        try {
            resendPending();
        } finally {
            lock.unlock();
        }
    }

    private void resendPending() {
        log.info("Retry send start");

        Set<String> userIds = redisTemplate.opsForZSet().rangeByScore(CouponRedisKeys.PENDING_KEY, 0,  System.currentTimeMillis() - 40000);

        log.info("Retry Send Target Size: {}개", userIds.size());

        for (String userId : userIds) {
            try {
                CompletableFuture<SendResult<String, CouponIssueMessage>> future = kafkaTemplate.send(KafkaCouponServiceV8.TOPIC, userId, new CouponIssueMessage(userId, System.currentTimeMillis()));
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("pending 재전송 실패 userId={}", userId, ex);
                    }
                });

                redisTemplate.opsForZSet().add(CouponRedisKeys.PENDING_KEY, userId, System.currentTimeMillis());
            } catch (Exception e) {
                log.info("send 재처리 중 오류:{}", userId, e);
                break;
            }
        }

        log.info("Retry send end");
    }
}
