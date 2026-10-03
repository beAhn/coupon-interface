package com.coupon.v2;

import com.coupon.common.CouponIssuer;
import lombok.Synchronized;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

// v2: v1 복사본 - Atomic, synchronized 실험용
@Service
public class CouponServiceV2 implements CouponIssuer {

    private AtomicInteger stock = new AtomicInteger(INITIAL_STOCK);
    private AtomicInteger issuedCount = new AtomicInteger(0);

    @Override
    public String version() {
        return "v2";
    }

    @Override
    public String description() {
        return "Atomic, synchronized 실험";
    }

    /**
     * Atmoic 자체는 원자적이기 때문에 stock.get()을 쓰레드 통과한만큼 재고 차감도 그대로 된다. 1만개 요청, 500개 쓰레드풀일 때 500개 내외가 1초(sleep)후 차감되어 -400~-500개
     * @Synchronized를 통해 동시성 제어를 하면 깔끔하게 처리되나 그만큼 시간 지연(100초)
     * TODO:꼭 동기화 필요한 부분만 Synchronized 처리해서 속도 향상 확인
     * @return
     */
    //@Synchronized
    @Override
    public boolean publish() {
        if (!stockDecrease()) {
            return false;
        }
        issue();

        return true;
    }

    // 재고 확인 + 차감 (동기화 구간)
    private boolean stockDecrease() {
        synchronized (this) {
            if (stock.get() <= 0) {
                return false;
            }
            stock.decrementAndGet();
            return true;
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
