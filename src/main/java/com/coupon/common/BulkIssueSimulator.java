package com.coupon.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// 서비스 앞단에서 동시 요청을 흉내내는 시뮬레이터
@Slf4j
@Component
public class BulkIssueSimulator {
    public static final int REQUEST_COUNT = 10_000;
    private static final int THREAD_COUNT = 500;

    public Result run(CouponIssuer issuer) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();


        for (int i = 0; i < REQUEST_COUNT; i++) {
            int submitCnt = i;
            //람다 내부 함수 바로 실행하지 않고 쓰레드풀 큐에 세팅(1만개)
            executor.submit(() -> {
                log.info("executor submit_{}", submitCnt);
                try {
                    startLatch.await(); // 출발 신호 대기(500개 실행 대기)
                    if (issuer.publish()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        startLatch.countDown(); // 동시 출발(finally 부분에서 카운팅)
        doneLatch.await(); //해당 래치 카운팅 끝날때까지 대기
        long elapsedMs = System.currentTimeMillis() - start;

        executor.shutdown();

        return new Result(REQUEST_COUNT, successCount.get(), elapsedMs);
    }

    public record Result(int requestCount, int successCount, long elapsedMs) {
    }
}
