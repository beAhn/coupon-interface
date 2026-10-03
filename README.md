# coupon-interface

**동시성 처리 학습을 위한 선착순 쿠폰 발급 프로젝트**

재고 100개 쿠폰에 1만 건의 요청이 동시에 몰리는 상황을 재현하고,
동시성 제어 방식을 버전(v1 → vN)별로 바꿔 가며 **정확성과 처리 시간을 측정**합니다.

## 목표

- 대량 동시 요청에서 발생하는 경쟁 조건(race condition)을 직접 재현하고 원인 분석
- 멀티스레드 동시성 제어 방식별 트레이드오프 비교 (정확성 vs 처리량)
- 단일 서버 → 다중 서버 분산 환경으로 단계적 확장

## 기술 스택

- Java 21, Spring Boot 4.1.1, Maven
- Thymeleaf (발급 테스트 화면)
- 현재 DB 없이 메모리 필드로 재고 관리 → 이후 버전에서 DB, Redis 등 추가 예정

## 테스트 환경

| 항목 | 값 |
|---|---|
| 초기 재고 | 100개 |
| 동시 요청 | 10,000건 |
| 스레드 풀 | 500개 (`Executors.newFixedThreadPool`) |
| 발급 지연 | `Thread.sleep(1000)` (DB 조회 등 가정) |

- `CountDownLatch`로 모든 스레드를 대기시킨 뒤 동시에 출발시켜 경쟁을 최대화
- 성공 건수는 시뮬레이터가 별도 `AtomicInteger`로 집계 → 서비스 내부 값과 대조해 검증

## 버전별 결과

| 버전 | 방식 | 남은 재고 | 발급 수 | 성공 | 처리 시간 | 결과 |
|---|---|---|---|---|---|---|
| v1 | 동기화 없음 (`int`) | -491 | 592 | 599 | 2,012ms | ❌ 초과 발급 + 누락 |
| v2-1 | `AtomicInteger` | 약 -490 | - | - | - | ❌ 초과 발급 |
| v2-2 | `synchronized` 메서드 전체 | 0 | 100 | 100 | 약 100초 | ✅ 정확, 매우 느림 |
| v2-3 | `synchronized` 확인+차감만 | 0 | 100 | 100 | 1,006ms | ✅ 정확, 빠름 |

### v1. 동기화 없음

```java
if (stock <= 0) return false;
sleep(1000);
stock--;
issuedCount++;
```

- **초과 발급 (check-then-act)**: 500개 스레드가 동시에 "재고 있음"을 확인하고 통과
- **lost update**: `stock--`, `issuedCount++`는 읽기 → 계산 → 쓰기 3단계라 동시 실행 시 값을 덮어씀
  - 재고: 100 - 599 = -499여야 하지만 -491 (8건 누락)
  - 발급 수: 599여야 하지만 592 (7건 누락)
- sleep이 없으면 경쟁 구간이 나노초 단위라 로컬에서 거의 재현되지 않음 → 지연을 넣어 틈을 넓혀 재현

### v2-1. AtomicInteger

```java
if (stock.get() <= 0) return false;
sleep(1000);
stock.decrementAndGet();
```

- lost update는 해결되었지만 초과 발급은 그대로
- Atomic은 **메서드 호출 하나만 원자적**으로 보장. `get()`과 `decrementAndGet()` 사이는 보호되지 않음

### v2-2. synchronized (메서드 전체)

- 정확하지만 sleep이 락 안에 있어 한 번에 1건씩 처리 → 성공 100건 × 1초 = 약 100초
- 정확성을 얻는 대신 처리량을 잃음

### v2-3. synchronized 범위 최소화

```java
public boolean publish() {
    if (!stockDecrease()) return false; // 락: 재고 확인 + 차감
    issue();                            // 락 밖: 발급 처리 (병렬)
    return true;
}
```

- 확인 → 차감(재고 예약) → 발급 처리 순서로 바꿔 느린 작업을 락 밖으로 분리
- 락 점유 시간이 마이크로초 수준 → 성공 100건의 발급 처리가 병렬 진행
- **약 100초 → 1초**로 단축
- 남은 과제: 락 밖 발급 처리가 실패하면 예약한 재고를 되돌리는 보상 처리 필요

## 로드맵

- [x] v1: 동기화 없이 문제 재현
- [x] v2: Atomic, synchronized 비교 및 락 범위 최소화
- [ ] v2-4: 락 없이 CAS(`compareAndSet`) / `decrementAndGet()` 반환값으로 해결
- [ ] v3: 다중 서버 환경에서 synchronized 한계 확인 → 분산 락 (DB 락, Redis)
- [ ] 이후: 메시지 큐 기반 비동기 발급, 방식별 처리량(TPS) 비교

## 실행 방법

```bash
./mvnw spring-boot:run
```

- `http://localhost:8080` 접속 → 버전 선택
- `/v1`, `/v2` 화면에서 단건 발급, 대량 발급(1만 건), 초기화 실행
