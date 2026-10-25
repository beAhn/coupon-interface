package com.coupon.v7;

import com.coupon.common.CouponIssuer;
import com.coupon.domain.entity.CouponPublishLog;
import com.coupon.domain.entity.CouponStock;
import com.coupon.domain.repository.CouponPublishLogRepository;
import com.coupon.domain.repository.CouponStockRepository;
import com.coupon.v4.CouponServiceV4;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// v7-2: v7-1 + 1인 1매 (issued Set으로 userId 중복 거절)
@Slf4j
@Service
@RequiredArgsConstructor
public class OnePerUserCouponServiceV7 implements CouponIssuer {
    private static final String STOCK_KEY = "coupon:stock:1";
    private static final String PENDING_KEY = "coupon:pending:1";   // Redis 승인 후 DB 미반영 요청
    private static final String ISSUED_KEY = "coupon:issued:1";     // 발급받은 userId (1인 1매)

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;
    private final CouponPublishLogRepository couponPublishLogRepository;
    private final CouponStockRepository couponStockRepository;

    // KEYS = [stock, pending, issued], ARGV = [userId, timestamp]
    // 반환: 남은 재고(>= 0) / -1 품절 / -2 중복
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            // 확인(재고) → 확인+등록(SADD) → 변경(DECR, ZADD). 거절은 상태 변경 전에만
            "local stock = tonumber(redis.call('GET', KEYS[1])) " +   // 키 없으면 GET은 false → tonumber로 nil

                    "if stock == nil or stock <= 0 then " +
                    "  return -1 " +
                    "end " +

                    "if redis.call('SADD', KEYS[3], ARGV[1]) == 0 then " +   // 0 = 이미 받은 userId
                    "  return -2 " +
                    "end " +

                    "local remain = redis.call('DECR', KEYS[1]) " +

                    "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1]) " +       // pending member도 userId

                    "return remain",
            Long.class);

    // INSERT 실패 시 보상: pending에 있을 때만 재고 복구 + issued에서 제거 (재요청 허용)
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then " +
                    "  redis.call('SREM', KEYS[3], ARGV[1]) " +
                    "  return redis.call('INCR', KEYS[1]) " +
                    "end " +

                    "return -1",
            Long.class);

    @Override
    public String version() {
        return "v7-2";
    }

    @Override
    public String description() {
        return "DB연동(JPA / Redis 재고 판정 + 1인 1매)";
    }

    // 화면/시뮬레이터용: userId가 없으니 요청마다 랜덤 (중복 없음)
    @Override
    public boolean publish() {
        return publish(UUID.randomUUID().toString()) == IssueResult.SUCCESS;
    }

    public IssueResult publish(String userId) {
        List<String> keys = List.of(STOCK_KEY, PENDING_KEY, ISSUED_KEY);

        //재고체크
        //루아스크립트로 재고체크와 userId를 키로 넣어서 SADD를 통해 중복체크를 한다.둘다 통과해야 처리가 된다.
        Long result = redisTemplate.execute(ISSUE_SCRIPT, keys, userId, String.valueOf(System.currentTimeMillis()));

        //재고체크 통과시 발급
        if (result >= 0) {
            try {
                CouponStock referenceById = couponStockRepository.getReferenceById(1L);
                CouponPublishLog couponPublishLog = new CouponPublishLog(referenceById, userId);

                couponPublishLogRepository.save(couponPublishLog);
            } catch(Exception e) {
                log.error("coupon rollback", e);
                redisTemplate.execute(ROLLBACK_SCRIPT, keys, userId);
                return IssueResult.ERROR;
            }

            redisTemplate.opsForZSet().remove(PENDING_KEY, userId);

            return IssueResult.SUCCESS;
        } else if (result == -1) {
            return IssueResult.SOLD_OUT;
        } else if (result == -2) {
            return IssueResult.DUPLICATE;
        }

        return IssueResult.ERROR;
    }

    @Override
    public void reset() {
        couponServiceV4.reset();

        int stock = couponServiceV4.getStock();
        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));

        redisTemplate.delete(List.of(PENDING_KEY, ISSUED_KEY));
    }

    @Override
    public int getStock() {
        String redisStock = redisTemplate.opsForValue().get(STOCK_KEY);
        return redisStock == null ? 0 : Integer.parseInt(redisStock);
    }

    @Override
    public int getIssuedCount() {
        return couponServiceV4.getIssuedCount();
    }
}
