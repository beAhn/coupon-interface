package com.coupon.v1;

import com.coupon.common.CouponIssuer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// v1: 싱글톤 빈의 필드(공유 상태)로 재고 관리 - 동기화 없음
@Slf4j
@Service
public class CouponServiceV1 implements CouponIssuer {

    private int stock = INITIAL_STOCK;
    private int issuedCount = 0;

    @Override
    public String version() {
        return "v1";
    }

    @Override
    public String description() {
        return "동기화 없음 (int 필드)";
    }

    @Override
    public boolean publish() {
        if (stock <= 0) {
            return false;
        }
        sleep(1000); // 확인~차감 사이 지연 (DB 조회 등 가정)
        stock--;
        issuedCount++;

        return true;
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
        stock = INITIAL_STOCK;
        issuedCount = 0;
    }

    @Override
    public int getStock() {
        return stock;
    }

    @Override
    public int getIssuedCount() {
        return issuedCount;
    }
}
