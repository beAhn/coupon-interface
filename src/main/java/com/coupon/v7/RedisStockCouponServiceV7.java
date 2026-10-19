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
import org.springframework.stereotype.Service;

// v7: 재고 판정을 Redis로 위임 (DB는 발급 로그 + 주기적 재고 현행화)
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockCouponServiceV7 implements CouponIssuer {
    private static final String STOCK_KEY = "coupon:stock:1";

    private final CouponServiceV4 couponServiceV4;
    private final StringRedisTemplate redisTemplate;
    private final CouponPublishLogRepository couponPublishLogRepository;
    private final CouponStockRepository couponStockRepository;

    @Override
    public String version() {
        return "v7";
    }

    @Override
    public String description() {
        return "DB연동(JPA / Redis 재고 판정)";
    }

    //DECR 원자적 처리로 동시성 보장 후 재고가 0 이상이면 바로 발급 처리
    @Override
    public boolean publish() {
        Long decrementVal = redisTemplate.opsForValue().decrement(STOCK_KEY);

        if (decrementVal >= 0) {
            CouponStock couponStock = couponStockRepository.getReferenceById(1L);
            couponPublishLogRepository.save(new CouponPublishLog(couponStock));
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
