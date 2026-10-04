package com.coupon.v3;

import com.coupon.common.CouponIssuer;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

// v3: v2 복사본 - CAS(compareAndSet) 실험용
@Slf4j
@Service
public class CouponServiceV3 implements CouponIssuer {

    private AtomicInteger stock = new AtomicInteger(INITIAL_STOCK);
    private AtomicInteger issuedCount = new AtomicInteger(0);

    @Override
    public String version() {
        return "v3";
    }

    @Override
    public String description() {
        return "CAS 실험";
    }

    @Override
    public boolean publish() {
        if (!stockDecrease()) {
            return false;
        }
        issue();

        return true;
    }

    /**
     * CAS로 처리(CPU단위 lock-free)
     * 비교+차감을 원자적으로 처리함(읽은 값이 그대로일 때)
     * @return
     */
    private boolean stockDecrease() {
        //while문은 쓰레드 충돌하여 CAS실패의 경우 재시도를 위함
        while(true) {
            //지역변수 할당으로 고정(확인값 == CAS기대값이어야함)
            int current = stock.get();

            //재고가 0이면 false로 나가고, 재고가 있으면 CAS처리
            if (current > 0) {
                boolean isSuccessCAS = stock.compareAndSet(current, current - 1);

                if (isSuccessCAS) {
                    return true;
                }
            } else {
                return false;
            }
        }
    }

    // 발급 처리 (락 밖, 병렬)
    private void issue() {
        sleep(1000); // 발급 가정(DB조회)
        issuedCount.incrementAndGet();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void reset() {
        stock = new AtomicInteger(INITIAL_STOCK);
        issuedCount = new AtomicInteger(0);
    }

    @Override
    public int getStock() {
        return stock.get();
    }

    @Override
    public int getIssuedCount() {
        return issuedCount.get();
    }
}
