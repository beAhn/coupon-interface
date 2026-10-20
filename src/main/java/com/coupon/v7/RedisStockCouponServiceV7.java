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

// v7: 재고 판정을 Redis로 위임 (DB는 발급 로그 + 주기적 재고 현행화)
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockCouponServiceV7 implements CouponIssuer {
    private static final String STOCK_KEY = "coupon:stock:1";
    private static final String PENDING_KEY = "coupon:pending:1";   // Redis 승인 후 DB 미반영 요청

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;
    private final CouponPublishLogRepository couponPublishLogRepository;
    private final CouponStockRepository couponStockRepository;

    // 재고 확인 + DECR + ZADD를 한 번에 실행 (재고 없으면 -1, 음수로 내려가지 않음)
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            "local stock = tonumber(redis.call('GET', KEYS[1])) " +
                    "if stock == nil or stock <= 0 then " +
                    "  return -1 " +
                    "end " +
                    "local remain = redis.call('DECR', KEYS[1]) " +
                    "redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1]) " +
                    "return remain",
            Long.class);

    // INSERT 실패 시 보상: 대기열에 있을 때만 ZREM + INCR (중복 보상 방지)
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then " +
                    "  return redis.call('INCR', KEYS[1]) " +
                    "end " +
                    "return -1",
            Long.class);

    @Override
    public String version() {
        return "v7";
    }

    @Override
    public String description() {
        return "DB연동(JPA / Redis 재고 판정)";
    }

    //DECR 원자적 처리로 동시성 보장 후 재고가 0 이상이면 바로 발급 처리
//    @Override
//    public boolean publish() {
//        Long decrementVal = redisTemplate.opsForValue().decrement(STOCK_KEY);
//
//        if (decrementVal >= 0) {
//            CouponStock couponStock = couponStockRepository.getReferenceById(1L);
//            couponPublishLogRepository.save(new CouponPublishLog(couponStock));
//            return true;
//        }
//        return false;
//    }

    //루아스크립트를 이용하여 원자적 재고 차감 및 발급(+롤백 시 +1 재고 처리 등)
    @Override
    public boolean publish() {
        //UUID(회원 ID역할)
        String requestId = UUID.randomUUID().toString();
        List<String> keys = List.of(STOCK_KEY, PENDING_KEY);

        //루아 스크립트로 재고 차감 후 펜딩(발급 여부 체크용)
        Long remain = redisTemplate.execute(ISSUE_SCRIPT, keys, requestId, String.valueOf(System.currentTimeMillis()));

        //재고가 있을경우 발급, 예외시 롤백, 정상발급시 펜딩키 ZREM
        if (remain >= 0) {
            try {
                CouponStock couponStock = couponStockRepository.getReferenceById(1L);
                couponPublishLogRepository.save(new CouponPublishLog(couponStock));
            } catch (Exception e) {
                log.error("coupon rollback", e);
                //pending 해제
                redisTemplate.execute(ROLLBACK_SCRIPT, keys, requestId);
                return false;
            }

            redisTemplate.opsForZSet().remove(PENDING_KEY, requestId);
            return true;
        }
        return false;
    }

    //레디스로 재고 위임
    @Override
    public void reset() {
        //재고 100 되돌리기
        couponServiceV4.reset();

        //100 재고 레디스로 넣기
        int stock = couponServiceV4.getStock();
        redisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));

        //펜딩키 제거
        redisTemplate.delete(PENDING_KEY);
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
