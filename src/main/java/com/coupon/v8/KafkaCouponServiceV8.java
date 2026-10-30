package com.coupon.v8;

import com.coupon.common.CouponIssuer;
import com.coupon.v4.CouponServiceV4;
import com.coupon.v7.IssueResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.coupon.v7.CouponRedisKeys.*;

// v8-1: v7-2 Redis 승인(재고·1인 1매)은 그대로, DB INSERT는 Kafka로 넘김 (이 클래스 = 프로듀서)
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaCouponServiceV8 implements CouponIssuer {

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, CouponIssueMessage> kafkaTemplate;

    public static final String TOPIC = "coupon-issue";   // 컨슈머 @KafkaListener도 사용
    private static final long SEND_TIMEOUT_SECONDS = 3;  // 브로커 다운 시 요청 스레드 최대 대기

    // v7-2 스크립트 재사용 (반환: 남은 재고(>= 0) / -1 품절 / -2 중복)
    private static final RedisScript<Long> ISSUE_SCRIPT = RedisScript.of(new ClassPathResource("scripts/v7-2/issue.lua"), Long.class);

    // 전송 실패 시 보상: pending에 있을 때만 재고 복구 + issued에서 제거
    private static final RedisScript<Long> ROLLBACK_SCRIPT = RedisScript.of(new ClassPathResource("scripts/v7-2/rollback.lua"), Long.class);

    @Override
    public String version() {
        return "v8-1";
    }

    @Override
    public String description() {
        return "Kafka 비동기 발급(Redis 승인 → 컨슈머 DB 반영)";
    }

    // 화면/시뮬레이터용: userId가 없으니 요청마다 랜덤 (중복 없음)
    @Override
    public boolean publish() {
        return publish(UUID.randomUUID().toString()) == IssueResult.SUCCESS;
    }

    public IssueResult publish(String userId) {
        List<String> keys = List.of(STOCK_KEY, PENDING_KEY, ISSUED_KEY);

        Long result = redisTemplate.execute(ISSUE_SCRIPT, keys, userId, String.valueOf(System.currentTimeMillis()));

        IssueResult approval = IssueResult.from(result);
        if (approval != IssueResult.SUCCESS) {
            return approval;
        }

        //승인 통과시 토픽 전송 (키 = userId → 같은 유저는 같은 파티션)
        try {
            CompletableFuture<SendResult<String, CouponIssueMessage>> future = kafkaTemplate.send(TOPIC, userId, new CouponIssueMessage(userId, System.currentTimeMillis()));

            //브로커 ack까지 대기 (실패·타임아웃은 예외)
            SendResult<String, CouponIssueMessage> sent = future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("sent userId={} partition={} offset={}", userId, sent.getRecordMetadata().partition(), sent.getRecordMetadata().offset());
        } catch (Exception e) {
            //타임아웃이면 이후 전송될 수도 있음 → Redis 미발급·DB 발급 불일치 가능 (v8-2)
            log.error("kafka send failed, rollback", e);
            redisTemplate.execute(ROLLBACK_SCRIPT, keys, userId);

            return IssueResult.ERROR;
        }

        // SUCCESS = 발급 확정, DB 반영 대기 (pending은 컨슈머가 INSERT 후 제거)
        return IssueResult.SUCCESS;
    }

    @Override
    public void reset() {
        couponServiceV4.reset();

        int stock = couponServiceV4.getStock();
        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));

        redisTemplate.delete(List.of(PENDING_KEY, ISSUED_KEY));
        // TODO: 토픽에 남은 메시지는? (reset 후 컨슈머가 옛 메시지를 INSERT할 수 있음)
    }

    @Override
    public int getStock() {
        String redisStock = redisTemplate.opsForValue().get(STOCK_KEY);
        return redisStock == null ? 0 : Integer.parseInt(redisStock);
    }

    // 비동기라 응답 직후엔 Redis 승인 수보다 작을 수 있음
    @Override
    public int getIssuedCount() {
        return couponServiceV4.getIssuedCount();
    }
}
