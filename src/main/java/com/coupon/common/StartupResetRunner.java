package com.coupon.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

// 기동 완료 시 전 버전 초기화 (DB 재고 100·발급 로그 삭제·Redis 키 정리)
// 주의: DB/Redis는 서버 2대 공유 → 테스트 중 한 대만 재시작해도 초기화됨. 끄려면 coupon.reset-on-startup=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coupon.reset-on-startup", havingValue = "true", matchIfMissing = true)
public class StartupResetRunner {

    private final List<CouponIssuer> issuers;

    @EventListener(ApplicationReadyEvent.class)
    public void resetAll() {
        issuers.forEach(CouponIssuer::reset);
        log.info("기동 시 초기화 완료: {}개 버전", issuers.size());
    }
}
