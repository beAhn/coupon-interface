package com.coupon.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// 서버 2대용 시뮬레이터: 같은 JVM 호출 대신 로드밸런서(nginx)로 HTTP 요청 → app1/app2로 분산
@Slf4j
@Component
public class DistributedIssueSimulator {
    private static final int REQUEST_COUNT = BulkIssueSimulator.REQUEST_COUNT;
    private static final int THREAD_COUNT = 500;

    // Docker 안: http://nginx (컨테이너의 localhost는 자기 자신) / Mac 로컬 실행: http://localhost
    @Value("${coupon.lb-url:http://localhost}")
    private String lbUrl;

    // L4 nginx는 연결 단위 분산 → HTTP/1.1 keep-alive 연결이 스레드 수만큼 생겨 고르게 나뉨
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // pathSupplier: 요청마다 경로 생성 (v7-2는 userId를 바꿔가며)
    public Result run(String version, Supplier<String> pathSupplier) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(REQUEST_COUNT);
        Counter counter = new Counter();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HttpRequest request = HttpRequest.newBuilder(URI.create(lbUrl + "/" + version + pathSupplier.get()))
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    counter.add(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    counter.error.incrementAndGet();   // 연결 실패, 타임아웃 등
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        startLatch.countDown();
        doneLatch.await();
        long elapsedMs = System.currentTimeMillis() - start;

        executor.shutdown();

        return new Result(REQUEST_COUNT, counter.success.get(), counter.soldOut.get(), counter.duplicate.get(),
                counter.error.get(), counter.app1.get(), counter.app2.get(), elapsedMs, lbUrl);
    }

    // 200 성공 / 409 품절·중복(message) / 그 외 오류. 응답 body의 serverName으로 서버별 집계
    private static class Counter {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger soldOut = new AtomicInteger();
        final AtomicInteger duplicate = new AtomicInteger();
        final AtomicInteger error = new AtomicInteger();
        final AtomicInteger app1 = new AtomicInteger();
        final AtomicInteger app2 = new AtomicInteger();

        void add(HttpResponse<String> response) {
            String body = response.body();
            if (response.statusCode() == 200) success.incrementAndGet();
            else if (response.statusCode() == 409 && body.contains("DUPLICATE")) duplicate.incrementAndGet();
            else if (response.statusCode() == 409) soldOut.incrementAndGet();
            else error.incrementAndGet();

            if (body.contains("\"serverName\":\"app1\"")) app1.incrementAndGet();
            else if (body.contains("\"serverName\":\"app2\"")) app2.incrementAndGet();
        }
    }

    public record Result(int requestCount, int successCount, int soldOutCount, int duplicateCount, int errorCount,
                         int app1Count, int app2Count, long elapsedMs, String lbUrl) {
    }
}
