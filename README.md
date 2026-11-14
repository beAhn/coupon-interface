# coupon-interface

**동시성 처리 학습을 위한 선착순 쿠폰 발급 프로젝트**

재고 100개 쿠폰에 1만 건의 요청이 동시에 몰리는 상황을 재현하고,
동시성 제어 방식을 버전(v1 → vN)별로 바꿔 가며 **정확성과 처리 시간을 측정**합니다.

## 목표

- 대량 동시 요청에서 발생하는 경쟁 조건(race condition)을 직접 재현하고 원인 분석
- 멀티스레드 동시성 제어 방식별 트레이드오프 비교 (정확성 vs 처리량)
- 단일 서버 → 다중 서버 분산 환경으로 단계적 확장

## 버전 지도

오랜만에 볼 때 여기부터. 코드는 `src/main/java/com/coupon/{버전}`, 화면은 `http://localhost/{경로}`.

| 버전 | 경로 | 핵심 클래스 | 한 줄 요약 | 다음 단계로 간 이유 |
|---|---|---|---|---|
| v1 | `/v1` | `CouponServiceV1` | 동기화 없이 `int` 재고 → 초과 발급 재현 | 경쟁 조건을 막을 도구 필요 |
| v2 | `/v2` | `CouponServiceV2` | Atomic, `synchronized` 범위 비교 (코드는 최종본 v2-3만) | Atomic은 연산 1개만 보호, 락은 느림 → 락 없는 방법 |
| v3 | `/v3` | `CouponServiceV3` | CAS(`compareAndSet`)로 락 없이 해결 | 메모리 재고라 서버 재시작·다중 서버 불가 → DB |
| v4-1 | (화면 없음) | `CouponServiceV4` | JPA + `@Transactional`만 → 202건 초과 발급 | 트랜잭션은 동시성 제어가 아님 |
| v4-2 | `/v4` | `ProxyCouponServiceV4` → `CouponServiceV4` | 트랜잭션 바깥 빈에서 `synchronized` (커밋 후 락 해제) | JVM 락은 서버 2대에서 쪼개짐(173건) → DB 락 |
| v5-1 | `/v5-1` | `PessimisticCouponServiceV5` | `SELECT ... FOR UPDATE` 비관적 락 | 대기 중 커넥션 점유, 품절 후에도 락 대기 |
| v5-2 | `/v5-2` | `OptimisticCouponServiceV5` | `@Version` 낙관적 락 (재시도 없음) | 핫 로우에서 충돌·FK 데드락 다수 |
| v5-3 | `/v5-3` | `ConditionalUpdateCouponServiceV5` | `UPDATE ... WHERE stock > 0` 쿼리 하나 | 여전히 DB 행 락 직렬 → 대기를 DB 밖으로 |
| v6-1 | `/v6` | `RedisLockCouponServiceV6` | Redis `SET NX` 스핀 락 + Lua 해제, 품절 빠른 반환 | 폴링 부하·기아, TTL 선택 어려움 |
| v6-2 | `/v6-2` | `RedissonLockCouponServiceV6`, `RedissonConfig` | Redisson (pub/sub 대기, watchdog) | 락 코드가 비즈니스에 섞임 |
| v6-3 | `/v6-3` | `AopRedissonLockPublisherV6`, `aop/*` | `@DistributedLock` AOP, `REQUIRES_NEW`로 커밋 후 해제 | 판정은 여전히 DB라 직렬 병목 → 판정을 Redis로 |
| v7-1 | `/v7-1` | `RedisStockCouponServiceV7`, `scripts/v7-1/*.lua` | Lua로 재고 확인+차감 원자 처리, DB는 로그 INSERT만, 실패 시 보상 | 1인 1매 조건 없음 |
| v7-2 | `/v7-2` | `OnePerUserCouponServiceV7`, `scripts/v7-2/*.lua` | 1인 1매 (`SADD issued`), 결과 분류 | 요청 경로에 DB INSERT가 남음 → 비동기 |
| v8-1 | `/v8-1` | `KafkaCouponServiceV8`, `CouponIssueConsumerV8` | Redis 승인 → Kafka → 컨슈머가 DB INSERT | 전송 실패 시 즉시 롤백 → 고객이 자리를 잃음 |
| v8-2 | `/v8-2` | `KafkaRetryCouponServiceV8`, `CouponPendingRetrySchedulerV8` | 전송 실패해도 성공 응답, pending 40초 지나면 재전송, UNIQUE 멱등 | 컨슈머가 계속 실패하는 메시지 처리 없음 |
| v8-3 | `/v8-2` (그대로) | `KafkaErrorConfig`, `CouponIssueDltConsumerV8` | 재시도 3회 → DLT → Redis 롤백 | DLT 롤백과 재전송이 어긋남 → 개선 방향만 기록(종료) |

**공통**
- `common/CouponController`: 버전별 컨트롤러의 부모 (발급, 대량 발급, 분산 대량 발급, 초기화)
- `common/CouponIssuer`: 버전마다 구현하는 인터페이스 (`publish`, `reset`, `version`)
- `common/DistributedIssueSimulator`: nginx 경유 1만 건, 서버별·결과별 집계
- `domain/`: 공용 엔티티·리포지토리. `CouponStock`의 `@Version`은 v5-2용이지만 모든 버전에 적용됨 (주의)
- `v7/CouponRedisKeys`: v7-1, v7-2, v8이 같은 Redis 키 공유 → 한 번에 한 버전씩 테스트

## 기술 스택

- Java 21, Spring Boot 4.1.1, Maven
- Thymeleaf (발급 테스트 화면)
- v1~v3: DB 없이 메모리 필드로 재고 관리
- v4~: Spring Data JPA (Hibernate 7), MySQL 8.0 (Docker Compose), HikariCP
- 다중 서버 테스트: Docker 앱 컨테이너 2대, nginx 1.27 (L4 `stream` 로드밸런서), k6
- v6~: Redis 7.4 (v6 분산 락: `SET NX` 스핀 락, Redisson / v7 재고 판정: `DECR`, Lua 스크립트)
- v8~: Kafka 4.2.1 (KRaft 단일 노드), `spring-boot-starter-kafka`

## 테스트 환경

| 항목 | 값 |
|---|---|
| 초기 재고 | 100개 |
| 동시 요청 | 10,000건 |
| 스레드 풀 | 500개 (`Executors.newFixedThreadPool`) |
| 발급 지연 | v1~v3: `Thread.sleep(1000)` (DB 조회 등 가정) / v4~: sleep 없이 실제 DB I/O |
| 커넥션 풀 | v4~: HikariCP 기본 10개 |

- `CountDownLatch`로 모든 스레드를 대기시킨 뒤 동시에 출발시켜 경쟁을 최대화
- 성공 건수는 시뮬레이터가 별도 `AtomicInteger`로 집계 → 서비스 내부 값과 대조해 검증

## 버전별 결과

| 버전 | 방식 | 남은 재고 | 발급 수 | 성공 | 처리 시간 | 결과 |
|---|---|---|---|---|---|---|
| v1 | 동기화 없음 (`int`) | -491 | 592 | 599 | 2,012ms | ❌ 초과 발급 + 누락 |
| v2-1 | `AtomicInteger` | 약 -490 | - | - | - | ❌ 초과 발급 |
| v2-2 | `synchronized` 메서드 전체 | 0 | 100 | 100 | 약 100초 | ✅ 정확, 매우 느림 |
| v2-3 | `synchronized` 확인+차감만 | 0 | 100 | 100 | 1,006ms | ✅ 정확, 빠름 |
| v3 | CAS (`compareAndSet`, 락 없음) | 0 | 100 | 100 | 약 1초 (v2-3과 비슷) | ✅ 정확, 빠름 |
| v4-1 | JPA + `@Transactional` (락 없음) | 0 | 202 | - | 약 1.6초 | ❌ 초과 발급 (재고는 0으로 정상처럼 보임) |
| v4-2 | 트랜잭션 바깥 빈에서 `synchronized` | 0 | 100 | - | 24~36초 | ✅ 정확, 느림 (JVM 1개 한정) |
| v5-1 | 비관적 락 (`SELECT ... FOR UPDATE`) | 0 | 100 | - | 16~20초 | ✅ 정확, 서버 간 공유 |
| v5-2 | 낙관적 락 (`@Version`), 재시도 없음 | 0 | 100 | - | 5.1초 | ⚠️ 요청 1만 건에선 정상처럼 보임 |
| v5-2 | 위와 동일, **요청 200건** | **70** | **30** | - | - | ❌ 재고가 남았는데 85% 충돌 실패 |
| v4-2 (서버 2대) | 시뮬레이터 동시 실행, `@Version` 있음 | 0 | 100 | - | - | ⚠️ synchronized는 깨졌지만 version 충돌로 롤백 |
| v4-2 (서버 2대) | 시뮬레이터 동시 실행, `@Version` 제거 | 0 | **158** | - | - | ❌ 서버마다 락이 따로라 초과 발급 |
| v4-2 (서버 2대) | k6 1만 건 → nginx | 0 | **173** | 173 | 8.2초 | ❌ 초과 발급 + 데드락 22건 |
| v5-1 (서버 2대) | k6 1만 건 → nginx | 0 | 100 | 100 | 4.0~7.3초 | ✅ DB 행 락은 서버 간 공유 |
| v5-2 (서버 2대) | k6 1만 건 → nginx, 재시도 없음 | 0 | 100 | 100 | 7.3~8.7초 | ⚠️ 정확하지만 롤백 1,401건 (데드락 1,302 + version 충돌 99) |
| (참고) v5-2에서 `@Version` 미적용 | 락 없는 JPA를 서버 2대로 | 0 | **199** | - | - | ❌ 동시 최대 20 트랜잭션이 겹쳐 읽음 |
| v5-3 (서버 2대) | 조건부 UPDATE (`WHERE stock > 0`) | 0 | 100 | 100 | 약 8초 | ✅ 데드락 0 |
| v6 (서버 2대) | Redis 분산 락 (`SET NX` 스핀 + Lua 해제) | 0 | 100 | 100 | 42.7초 | ✅ 정확, 최대 대기 36.7초 (기아) |
| v6 + 품절 빠른 반환 (서버 2대) | 락 밖 재고 확인 → 즉시 거절 | 0 | 100 | 100 | 약 11.8초 | ✅ (OSIV 켜면 2,000건+ 초과 발급) |
| v6-3 (서버 2대) | Redisson + AOP `@DistributedLock` | 0 | 100 | 100 | 19.6초 | ✅ 오류 0 |
| v7 도입 `DECR` 단독 (서버 2대) | Redis `DECR`로 재고 판정, DB는 발급 로그 INSERT만 | Redis **-9900** | 100 | 100 | 3.7초 | ⚠️ 정확하지만 재고 음수, INSERT 실패 시 미달 발급 |
| v7-1 (서버 2대) | Lua(재고 확인 + `DECR` + pending 등록) + 실패 보상 | Redis 0 | 100 | 100 | 2.6초 | ✅ pending 0 |
| v7-1 + INSERT 10% 강제 실패 | 롤백 경로 검증 | Redis 0 | 100 | 100 | - | ✅ 승인 116 = 성공 100 + 롤백 16 |
| v7-2 1인 1매 (서버 2대, 회원 150) | Lua(재고 확인 → `SADD` issued → `DECR` → pending) | Redis 0 | 100 | 100 | 3.4초 | ✅ 중복 거절 69, 2장 받은 회원 0 |
| v8-1 (서버 2대, 회원 150) | v7-2 Redis 승인 + Kafka로 DB 반영 비동기화 | Redis 0 | 100 | 100 | 7.1초 | ✅ 중복 63, 오류 0, 응답 직후 DB 72건 → 잠시 후 100건 |
| v8-2 + 전송 10% 강제 실패 (서버 2대, 회원 150) | pending 재전송 스케줄러 | Redis 0 | 100 | 100 | - | ✅ 강제 실패 10 → pending 10·DB 90 → 재전송 후 pending 0·DB 100, 중복 회원 0 |
| v8-3 + MySQL 중단 (1건) | 컨슈머 재시도 3회 → DLT 롤백, 그동안 재전송 복사본 5건 | Redis 100 (롤백) | **DB 1** | - | 약 3분 반 후 DLT | ❌ DLT 롤백 후 남은 복사본이 INSERT → DB 발급·Redis 롤백 어긋남 |

**분산 대량 발급 버튼 재측정 (2026-10-31, 서버 2대, v5 3종)**

| 버전 | 성공 | 품절 | 오류 | 시간 | 비고 |
|---|---|---|---|---|---|
| v5-1 비관적 락 | 100 | 9,900 | 0 | 151.7초 / 46.8초 | 같은 코드로 편차 큼 |
| v5-2 낙관적 락 | 100 | 8,163 | 1,737 | 93초 | 데드락 1,629 + version 충돌 108 |
| v5-2 + `flush()` | 100 | - | 1,489 | - | 데드락 0, 전부 version 충돌 |
| v5-3 조건부 UPDATE | 100 | 9,900 | 0 | 61.8초 | 데드락 0 |

> 이 표의 시간은 호스트 메모리 부족(스왑 약 16~17GB)이 겹쳐 우열 비교에 쓰지 않습니다. 정확성과 실패 원인만 비교합니다.

> v4~ 결과는 재고가 아니라 **발급 로그 수**로 판단합니다. lost update가 나도 재고는 0으로 끝나기 때문입니다.

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
- 락 밖에서 동시에 증가하는 `issuedCount`는 다시 `AtomicInteger`가 필요 (락 안에서만 바뀌는 `stock`은 `volatile int`로도 충분)
- 남은 과제: 락 밖 발급 처리가 실패하면 예약한 재고를 되돌리는 보상 처리 필요

> 동시성 검증은 실행 순서가 아니라 결과로 합니다. 스레드 실행 순서는 매번 달라지지만, 재고 ≥ 0 · 성공 수 = 발급 수 = 100이 항상 지켜져야 합니다.

### v3. CAS (lock-free)

```java
private boolean stockDecrease() {
    while (true) {
        int current = stock.get();
        if (current > 0) {
            if (stock.compareAndSet(current, current - 1)) {
                return true;   // 내가 읽은 값 그대로일 때만 차감 성공
            }
            // 실패: 다른 스레드가 먼저 변경 → 다시 읽고 재시도
        } else {
            return false;      // 재고 없음
        }
    }
}
```

- 락 없이 "읽은 값이 그대로일 때만 교체, 아니면 재시도"로 확인과 차감 사이 끼어들기를 감지
- 대기(블로킹) 없이 겹친 스레드만 재시도 → 락을 쥔 스레드가 멈춰도 다른 스레드는 진행
- **처리 시간은 v2-3과 비슷** (sleep을 빼도 비슷)
  - 락 범위를 이미 최소화해서 확인+차감 비용이 둘 다 매우 짧음 → 시뮬레이터 자체 비용에 묻힘
  - 성능을 가른 것은 동기화 방식이 아니라 **락 범위** (v2-2 → v2-3)
- 선택 기준은 성능보다 용도: CAS는 변수 1개만 보호, 여러 값을 함께 바꾸는 로직은 synchronized가 적합
- 공통 한계: synchronized와 CAS 모두 JVM 1개 안에서만 유효 → 다중 서버에선 깨짐

### v4-1. JPA + @Transactional (락 없음)

```java
@Transactional
public boolean publish() {
    CouponStock couponStock = couponStockRepository.findById(1L).orElseThrow();
    if (couponStock.decrease()) {   // 엔티티 안에서 > 0 검사
        couponPublishLogRepository.save(new CouponPublishLog(couponStock));
        return true;
    }
    return false;
}
```

- 결과: 재고 0, 발급 로그 202건 → **초과 발급 102건**
- `@Transactional`은 원자성(전부 반영 또는 전부 취소)을 보장할 뿐, 다른 트랜잭션이 같은 행을 동시에 읽는 것은 막지 않음
  - MySQL `REPEATABLE_READ`의 일반 SELECT는 락 없는 스냅샷 읽기 → 여러 트랜잭션이 같은 재고(예: 50)를 읽음
  - 더티 체킹은 DB가 아니라 조회 시점 스냅샷과 비교 → 각자 `UPDATE stock = 49`로 덮어씀 (lost update)
- Hikari 풀을 1로 줄이면 정확히 100건 → 트랜잭션이 커밋까지 커넥션을 쥐고 있어 우연히 직렬화된 것. 해결책 아님
- ⚠️ v5-2에서 공통 엔티티에 `@Version`을 추가한 이후로는 초과 발급 대신 `OptimisticLockException`이 발생

### v4-2. @Transactional + synchronized

**실패 시도: `@Transactional` 메서드에 바로 `synchronized` → 약 200건**

```
프록시.publish()              ← @Transactional
  트랜잭션 시작
  └ 실제객체.publish()        ← synchronized 범위는 여기만
       SELECT → decrease() → INSERT
       return  ← 락 해제
  flush (UPDATE), commit      ← 락 밖
```

- 락은 메서드 `return`에서 풀리고, UPDATE와 commit은 그 뒤 프록시가 실행 → 다음 스레드가 커밋 전 값을 읽음

**해결: 트랜잭션 바깥 빈(`ProxyCouponServiceV4`)에서 `synchronized`**

```
ProxyCouponServiceV4.publish()  ← synchronized: 락 획득
  └ couponServiceV4.publish()   ← 트랜잭션 프록시
       시작 → SELECT → decrease → INSERT → UPDATE → commit
  return                         ← 락 해제 (커밋 이후)
```

- 100건 정확, 하지만 **24~36초**: 트랜잭션 전체가 한 줄로 직렬화. 품절 후 9,900건도 줄을 섬
- `synchronized`는 JVM 1개 안에서만 유효 → 다중 서버에선 깨짐 (아래 다중 서버 테스트)

### v5-1. 비관적 락 (SELECT ... FOR UPDATE)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from CouponStock c where c.id = :id")
Optional<CouponStock> findByIdForUpdate(@Param("id") Long id);
```

- 100건 정확, **16~20초** (v4-2 대비 단축)
- PK 조건이라 해당 행 1개에만 X 락. 락은 commit/rollback까지 유지
- JVM 락과 비교: 직렬화 구간이 `커넥션 획득 ~ 반납` 전체에서 `FOR UPDATE ~ commit`으로 줄어듦 → **DB에서도 락 범위가 성능을 결정**
- 락이 DB에 있어 서버가 여러 대여도 공유됨
- 단점: 대기 중에도 커넥션을 쥐고 있음 (풀 고갈 위험), 품절 이후 요청도 락을 기다려 "재고 0"을 확인
- `PESSIMISTIC_READ`(S 락)로 읽고 수정하면 데드락 → 수정까지 하면 X 락

### v5-2. 낙관적 락 (@Version)

```sql
-- 락 없이 읽기 (version도 함께)
SELECT stock_quantity, version FROM coupon_stock WHERE id = 1;   -- 50, v7

-- 커밋 직전, 읽은 version과 비교
UPDATE coupon_stock SET stock_quantity = 49, version = 8
WHERE id = 1 AND version = 7;   -- 0 rows면 ObjectOptimisticLockingFailureException
```

- 요청 1만 건: 100건 정확, **5.1초** (v5-1보다 3~4배 빠름)
  - 락 방식 차이보다 **품절 이후 요청 처리 방식** 때문: 재고 0이면 엔티티가 바뀌지 않아 UPDATE 없이 락 없이 반환
- **요청 200건: 재고 70 남음, 발급 30건**
  - 충돌난 요청은 재시도 없이 그 자리에서 실패 → 1만 건일 땐 뒤에 온 요청이 남은 재고를 채워 정상처럼 보였을 뿐
  - DB에서 동시에 경합하는 건 Hikari 커넥션 10개 → 라운드마다 10개가 같은 version을 읽고 1개만 성공
- 예외는 커밋 시점에 트랜잭션 프록시에서 발생 → 재시도는 트랜잭션 바깥에서 새 트랜잭션으로
- 결론: 한 행에 요청이 몰리는 선착순 발급에는 맞지 않음. 충돌이 드문 곳(요청이 여러 행으로 분산)에 적합

| | 비관적 (v5-1) | 낙관적 (v5-2) |
|---|---|---|
| 막는 시점 | 읽을 때 (`FOR UPDATE`) | 쓸 때 (`WHERE version = ?`) |
| X 락 보유 | 읽기 ~ commit | UPDATE ~ commit |
| 충돌 시 | 대기 | 예외 → 실패 또는 재시도 |
| 품절 이후 | 락 대기 후 확인 | 락 없이 즉시 반환 |

### 다중 서버 테스트 (서버 2대 + L4 로드밸런서)

> 동시성 제어 방식이 아니라 **테스트 환경**이라 버전 번호 없이, 기존 v4-2·v5-1을 그대로 서버 2대에서 검증합니다.

```
k6 / curl → nginx :80 (L4, stream 라운드로빈)
              ├→ app1 :8080 ─┐
              └→ app2 :8080 ─┴→ MySQL (공유 재고)
```

- 같은 이미지(`coupon-app`)로 컨테이너 2대, 환경변수 `SERVER_NAME`만 다르게
- nginx `stream` 모듈로 **TCP 연결 단위** 분산. 요청 단위 확인은 API 응답의 `serverName`으로
  - L4는 TCP 연결이 맺어질 때(HTTP 데이터 도착 전) 서버를 고름 → 같은 연결의 요청은 계속 같은 서버
- 발급 API: `POST /{v}/api/issue` → `{"isSuccess": true, "serverName": "app1", ...}` (200 성공 / 409 품절 / 500 충돌 예외)

**v4-2(synchronized)를 서버 2대로**

```
app1 (락 A)                    app2 (락 B)   ← 서로의 락을 모름
SELECT stock → 50              SELECT stock → 50
decrease() → 49                decrease() → 49
INSERT 로그                     INSERT 로그
UPDATE = 49, commit            UPDATE = 49, commit   → 발급 2건, 재고 1 감소
```

- `@Version` 제거 시 **158건** → 재고 값 100개 중 58개를 두 서버가 겹쳐 읽음 (서버마다 1개씩, 동시 최대 2 → 이론상 최대 200)
- `@Version`이 있으면 100건 → 늦게 커밋한 쪽이 `WHERE version = ?`에서 0 rows → 예외·롤백. DB에 있는 값으로 막는 락은 서버 간 공유됨
- 결론: **JVM 락은 JVM 수만큼 쪼개진다.** 기준은 물리 서버 대수가 아니라 JVM 프로세스 수

| 구성 | 동시 트랜잭션 | 발급 |
|---|---|---|
| v4-1 락 없음 (서버 1대, 풀 10) | 최대 10 | 202 |
| v4-2 서버 1대 | 1 | 100 |
| v4-2 서버 2대 | 최대 2 | 158 |

> 위 158건은 각 서버의 시뮬레이터를 동시에 실행(총 2만 건)해 정확성만 확인한 결과입니다.

**k6로 측정 (1만 건 → nginx → app1/app2)**

| | v4-2 (synchronized) | v5-1 (비관적 락) | v5-2 (낙관적 락, 재시도 없음) |
|---|---|---|---|
| 발급 | 173 | 100 | 100 |
| 품절 (409) | 9,805 | 9,900 | 8,499 |
| 오류 (500) | 22 (데드락) | 0 | 1,401 (데드락 1,302 + version 충돌 99) |
| 전체 시간 | 8.2초 | 7.3초 (워밍업 후 4.0초) | 10.4초 (워밍업 후 7.3~8.7초) |

- 발급 수의 범위: `100 ≤ 발급 ≤ 100 × 동시에 실행될 수 있는 트랜잭션 수`. v4-2 서버 2대는 최대 2개 → 100~200 사이 (173 = 재고 값 100개 중 73개를 두 서버가 겹쳐 읽음)

- v5-1은 서버 2대에서도 정확: `FOR UPDATE`의 X 락이 MySQL에 있어 두 서버가 같은 락을 공유
- v5-1이 더 빠름: 줄은 1개(v4-2는 서버마다 1개씩 2개)지만 직렬 구간이 `FOR UPDATE ~ commit`으로 짧음

**v4-2에서 나온 데드락: 외래키의 S 락**

`coupon_publish_log.coupon_stock_id`는 `coupon_stock(id)`를 참조하는 외래키라, INSERT 시 InnoDB가 부모 행에 S 락을 걸고 커밋까지 유지합니다.

| 순서 | app1 | app2 | `coupon_stock` id=1 |
|---|---|---|---|
| 1 | 발급 로그 INSERT → S 락 | | app1: S |
| 2 | | 발급 로그 INSERT → S 락 (S끼리 호환) | app1: S, app2: S |
| 3 | UPDATE → X 락 요청, app2의 S 때문에 대기 | | |
| 4 | | UPDATE → X 락 요청, app1의 S 때문에 대기 | 순환 대기 → 한쪽 롤백 |

- Hibernate는 IDENTITY라 `save()` 시점에 INSERT, 재고 UPDATE는 커밋 직전 flush → 항상 S(INSERT) → X(UPDATE) 순서
- 서버 1대 v4-2는 트랜잭션이 1개씩이라 S 락이 겹칠 일이 없었음
- v5-1은 처음부터 `FOR UPDATE`로 X 락을 잡아 발생하지 않음
- S 락은 테이블 락이 아니라 **id=1 행(PK 인덱스 레코드) 하나**에만 걸림 (`RECORD LOCKS ... lock mode S locks rec but not gap`)

**v5-2(낙관적 락)를 서버 2대로**

- 일반 SELECT는 락 없이 MVCC 스냅샷으로 읽음 → 여러 트랜잭션이 같은 version으로 동시에 진행
- 롤백 1,401건 중 **1,302건이 외래키 데드락**, version 충돌은 99건뿐 → UPDATE에서 version을 비교하기도 전에 INSERT의 S 락끼리 순환 대기
- INSERT가 UPDATE보다 먼저 나가도 초과 발급은 없음: 같은 트랜잭션이라 UPDATE가 실패하면 INSERT도 함께 롤백 (원자성)
- 시간도 워밍업 후 7.3~8.7초로 v5-1(4.0초)보다 약 2배 느림 (서버 1대 시뮬레이터에선 v5-2가 더 빨랐음). 재고가 남은 초반에 데드락·롤백으로 느리고, 품절 이후엔 쓰기가 없어 빠름. 비관적 락은 반대로 품절 이후에도 `FOR UPDATE`로 한 줄
- 결론: 정확성은 지키지만, 한 행에 요청이 몰리면 롤백 비용이 큼. 재시도를 넣는다면 데드락(`CannotAcquireLockException`)도 대상에 포함해야 함

- 발생 조건: FK로 부모 행을 참조하는 자식 INSERT 후 같은 부모 행을 UPDATE하는 트랜잭션이 같은 행에 동시에 몰릴 때

**v5-2에 `flush()` 추가: UPDATE를 INSERT보다 먼저**

```java
if (couponStock.decrease()) {
    couponStockRepository.flush();   // UPDATE 먼저 → X 락 획득
    couponPublishLogRepository.save(new CouponPublishLog(couponStock));
    return true;
}
```

- X 락을 먼저 잡아 S→X 경로가 없어짐 → INSERT의 S 락은 이미 가진 X 락으로 충족, 뒤 트랜잭션은 X 락 대기 후 version 불일치로 0행
- 데드락 1,629 → **0**, version 충돌 108 → **1,489**. 실패 원인만 바뀌고 재시도가 없으면 요청은 여전히 실패

**v5-3. 조건부 UPDATE**

```java
@Modifying
@Query("update CouponStock c set c.stockQuantity = c.stockQuantity - 1 where c.id = :id and c.stockQuantity > 0")
int updateCouponStockByConditionalUpdate(@Param("id") Long id);   // 1 성공, 0 품절
```

- 재고 확인과 차감을 쿼리 하나로 처리. 재고 엔티티를 조회하지 않고, 성공 시 `getReferenceById` 프록시로 FK 값만 채워 INSERT
- 비교 후 변경은 CAS와 닮았지만 경합 시 실패·재시도가 아니라 **행 X 락 대기 후 최신 값으로 재평가** → 재고가 0일 때만 실패 (v5-2의 `WHERE version = ?`는 재고가 남아도 실패)
- UPDATE가 먼저라 FK S→X 데드락 경로가 없음. 서버 2대 100건, 오류·데드락 0
- 품절 요청도 UPDATE로 X 락을 거침 (app1 요청 4,958건 = `update` 로그 4,958건) → DB 병목은 남음
- JPQL 벌크 연산은 더티 체킹을 거치지 않아 `@Version`이 오르지 않음 (재고 100 차감에 version 변화 없음). 같은 행에 낙관적 락과 섞으면 낙관적 락이 이 변경을 감지하지 못함

**k6는 Docker 네트워크 안에서 실행**

Mac에서 `localhost:80`으로 보내면 VU 500개가 동시에 연결할 때 Docker Desktop 포트 포워딩 구간에서 `connection reset by peer`가 발생했습니다 (v5-1에서 350건, 앱 로그에는 예외 없음). 같은 테스트를 Docker 네트워크 안에서 실행하면 0건입니다. 그래서 k6를 compose 서비스(`profiles: ["test"]`)로 두고 `docker compose run`으로 실행합니다.

**측정 시 주의**

- 코드를 바꾸면 `./mvnw clean package` (`clean` 없으면 옛 jar가 남을 수 있음) → `docker compose up -d --build` → `docker compose restart nginx`. IntelliJ 실행은 Mac의 별도 앱이라 컨테이너에 반영되지 않음
- 같은 조건으로 3회 이상, 첫 회는 워밍업으로 제외 (v5-1이 7.3초 → 워밍업 후 4.0초)
- k6 `http_req_failed`는 200~399 밖을 모두 실패로 셈 → 409 품절도 포함 (v5-1 정상이 99%)

### v7. Redis 재고 판정

> v6까지 Redis는 "락"이었고 재고 판정은 DB에서 했습니다. v7은 **재고 판정 자체를 Redis 원자 연산으로** 옮기고, DB에는 발급 로그만 INSERT합니다.
>
> Lua 본문은 `src/main/resources/scripts/v7-1`, `v7-2`의 `.lua` 파일에 있고, 키는 `CouponRedisKeys`에 모았습니다. v7-1·v7-2·v8-1·v8-2는 같은 Redis 키를 공유하므로 한 번에 한 버전씩 테스트합니다. v8-2 스케줄러는 어떤 버전을 테스트하든 항상 돌기 때문에, 다른 버전에서 40초 넘게 남은 pending도 Kafka로 재전송합니다.

**v7 도입. `DECR` 단독** (코드는 주석으로 유지)

```java
Long remain = redisTemplate.opsForValue().decrement("coupon:stock:1");
if (remain >= 0) { /* 발급 로그 INSERT */ return true; }
return false;
```

- `DECR`은 Redis 명령 1개라 락 없이도 승인이 정확히 100번
- 재고 행 UPDATE가 없어서 v4-2·v5-2에서 본 외래키 S→X 데드락 경로 자체가 없음
- 서버 2대 k6: 100건, 오류 0, **3.7초** (v6-3 19.6초)
- 문제 1: 품절 요청도 `DECR` → Redis 재고 **-9900**
- 문제 2: `DECR` 후 INSERT가 실패하면 재고만 줄고 발급은 안 됨 → **미달 발급**

**v7-1. Lua 스크립트 + 실패 보상**

```lua
-- ISSUE_SCRIPT: 재고 확인 + DECR + pending 등록을 한 번에
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then return -1 end
local remain = redis.call('DECR', KEYS[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])   -- coupon:pending:1 <시각> <requestId>
return remain

-- ROLLBACK_SCRIPT: pending에 있을 때만 재고 복구 (중복 보상 방지)
if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then return redis.call('INCR', KEYS[1]) end
return -1
```

```
ISSUE_SCRIPT 승인 → INSERT 성공 → ZREM (pending 해제)          → 발급 확정
                  → INSERT 실패 → ROLLBACK_SCRIPT (ZREM + INCR) → 재고 원복, 다음 요청이 가져감
                  → 둘 다 아님 (서버 다운 등) → pending에 남음  → 결과 미확인
```

- Java에서 명령 2개를 따로 보내면 사이에 다른 요청이 끼어듦 → Lua 스크립트로 묶어 원자 실행
- 품절이면 `DECR`하지 않아 재고가 음수로 내려가지 않음
- 성공 후 `ZREM`은 `try` 밖에 둠. `try` 안이면 INSERT 커밋 후 `ZREM` 실패가 보상(INCR)으로 이어져 초과 발급
- 결과: 100건, Redis 재고 0, pending 0, 2.6초
- 롤백 검증 (INSERT 전 10% 확률 예외): 발급 100, 롤백 16 → **승인 116 = 성공 100 + 롤백 16**

**v7-2. 1인 1매** (`/v7-2/api/issue-user?userId=`)

```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then return -1 end                 -- 품절
if redis.call('SADD', KEYS[3], ARGV[1]) == 0 then return -2 end  -- 중복 (coupon:issued:1)
local remain = redis.call('DECR', KEYS[1])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])                     -- pending member = userId
return remain

-- ROLLBACK_SCRIPT: ZREM == 1이면 SREM issued + INCR (발급 실패한 사람은 재요청 가능)
```

- pending은 성공 시 지워져 "처리 중"만 막음 → "이미 받은 사람"은 지우지 않는 issued Set으로 따로 관리
- 거절 조건은 상태 변경 전에 확인. `SADD`는 확인과 등록이 명령 1개라 `SISMEMBER`가 필요 없고, 재고 확인 **뒤**에 둠 (앞이면 품절 요청자도 issued에 등록됨)
- 응답: 200 `SUCCESS` / 409 `SOLD_OUT`·`DUPLICATE` / 500 `ERROR` (`IssueResult`)
- 발급 로그에 `user_id` 저장 → pending의 userId와 DB를 대조할 수 있게 됨 (복구 준비)
- 결과(k6 1만 건, 회원 150 = 인당 약 67회): 발급 100, 중복 69, 품절 9,831, 오류 0. Redis issued 100, DB 회원 100명·2장 받은 회원 0
- 중복이 69뿐인 이유: 재고 확인이 먼저라 **품절 이후의 중복 요청은 `SOLD_OUT`으로 집계**. 품절 후에도 "이미 받음"을 알려주려면 `SISMEMBER`를 재고 확인 앞에 둠 (명령 1개 증가)
- 회원 수는 재고보다 많아야 품절 경로까지 검증됨. `USERS` 1000 ≈ 인당 10회, 5000 ≈ 2회

**남은 과제**

- pending 복구: 오래된 pending의 userId를 발급 로그와 대조해 재발급·보상
- DB `coupon_stock` 재고 현행화

### v8. Kafka 비동기 발급

> 동시성 판정은 v7-2 Redis Lua가 그대로 맡고, **확정된 발급을 DB에 기록하는 일을 Kafka로 넘깁니다.** 주제가 동시성에서 비동기 처리 신뢰성으로 바뀌어 v8로 분리했습니다.

```
요청 스레드(프로듀서)                     컨슈머 (app1·app2, concurrency 2)
Redis Lua 승인 → send(topic, userId, msg)
              → get(3초) 브로커 ack        → INSERT 발급 로그
              → 200 SUCCESS                → ZREM pending
```

**v8-1. 기본 흐름** (`/v8-1/api/issue-user?userId=`)

- SUCCESS의 의미: 발급 확정, DB 반영 대기. 응답 직후 화면 발급 수 72 → 잠시 후 100 (최종적 일관성)
- 메시지 키 = `userId`: 같은 유저의 이벤트 순서 보장 + 파티션 분산. 중복 요청은 Redis `SADD`가, 같은 메시지 재수신은 컨슈머 멱등 처리가 담당
- `send()`는 빈 `CompletableFuture`를 바로 반환 → 응답에 전송 결과를 반영하려고 `get(3초)`로 대기. 실패하면 `rollback.lua`로 보상 후 500
- `send()` 자체가 메타데이터 대기로 막힐 수 있어 `max.block.ms` 3000으로 축소. `get` 타임아웃은 전송 취소가 아님 → 보상 후 늦게 전송되는 불일치는 v8-2 과제
- 토픽 `coupon-issue`: 파티션 3, 복제 수 1. 컨슈머 4개 중 3개가 파티션을 맡고 1개는 대기
- 결과: 요청 1만, 성공 100, 품절 9,837, 중복 63, 오류 0. 파티션별 메시지 29 / 34 / 37, LAG 0. Redis 재고 0, pending 0, issued 100, DB 100건

**v8-2. pending 재전송** (`/v8-2/api/issue-user?userId=`)

v8-1은 전송이 실패하면 즉시 보상해 고객이 자리를 잃습니다. v8-2는 **승인을 유지하고, 전송되지 않은 건을 스케줄러가 다시 보냅니다.**

```
요청 스레드: Redis Lua 승인(pending 등록) → send 실패 → 로그만 남기고 200 SUCCESS
스케줄러(5초 주기, 락을 얻은 1대): 40초 지난 pending 조회 → send → score를 현재 시각으로 갱신
컨슈머: INSERT → ZREM pending (UNIQUE 위반이면 이미 처리된 건으로 보고 ZREM)
```

- pending을 지우는 곳은 컨슈머뿐: 전송 성공이 아니라 DB 반영까지 끝나야 완료
- score 갱신: 갱신하지 않으면 40초가 지난 건을 5초마다 다시 보내 장애 중 토픽에 중복 메시지가 쌓임. 갱신하면 같은 건은 40초마다 전송. 이때 score는 승인 시각이 아니라 마지막 전송 시도 시각
- `send()` 실패 2종: 호출 중 즉시 실패(`max.block.ms` 초과 등)는 `catch`에서 로그 후 `break`, 남은 건은 다음 주기로. 반환 후 실패는 `whenComplete`에서 로그
- 스케줄러 중복 실행 방지: Redisson `tryLock()`을 대기 없이 시도, 실패하면 이번 주기를 건너뜀. 서버가 죽으면 watchdog 만료 후 다른 서버가 이어받음
- 락이 있어도 메시지 중복은 가능(전송 후 score 갱신 전 서버 사망 등) → 멱등성은 `UNIQUE (coupon_stock_id, user_id)`가 담당
- 결과(전송 10% 강제 실패, 서버 2대 분산 버튼): 강제 실패 10(app1 4, app2 6) → 중간 확인 pending 10·DB 90 → 재전송 후 pending 0·DB 100, 회원 100명, Redis 재고 0, issued 100, LAG 0
- 결과(MySQL 약 5분 50초 중단, 발급 6건): 요청은 모두 200, 재전송 40초 간격 8회로 메시지 54건(최초 6 + 재전송 48). 파티션마다 첫 메시지는 약 5분 뒤 건너뛰어 3건 제외. 복구 후 나머지 51건 = INSERT 6 + UNIQUE 위반 45, 최종 pending 0·DB 6·중복 0, LAG 0

**v8-3. 컨슈머 실패 재시도·DLT** (v8-2에 추가, 별도 엔드포인트 없음)

- `DefaultErrorHandler`: 30초 간격 3회 재시도, `IllegalArgumentException`은 재시도 없이 바로 DLT(`coupon-issue-dlt`, 파티션 3)
- DLT 컨슈머(별도 그룹): 원인 로그 + v7-2 `rollback.lua`로 재고·issued 복구, pending 삭제 (pending에 있을 때만 → 중복 수신해도 1번)
- 결과(잘못된 userId): 즉시 DLT → Redis 복구 확인
- 결과(MySQL 중단, 1건): 시도 1회 약 60초(커넥션 대기 30초 + 백오프 30초), 약 3분 반 뒤 DLT 롤백(재고 100, pending·issued 0). 그동안 스케줄러가 복사본 5건 발행 → MySQL 복구 후 INSERT 1 + UNIQUE 위반 4 → **DB 발급, Redis 롤백으로 어긋남** (DLT 롤백은 토픽에 이미 들어간 복사본을 지우지 못함)
- 알려진 한계: 일시 장애와 영구 실패를 같은 롤백으로 처리 → 성공 응답을 받은 사용자가 탈락할 수 있음. 개선 방향: 컨슈머가 INSERT 전 pending 확인, 일시 장애는 DLT 재처리, 영구 실패만 롤백 + 알림

**남은 과제**

- v8-2 추가 실패 시나리오: Kafka 중단·복구
- v8-3 개선: INSERT 전 유효성(pending) 확인, 일시 장애 DLT 재처리
- DB 재고 현행화 (v7·v8은 DB 재고 컬럼을 갱신하지 않음)
- 토픽 자동 생성(`NewTopic`), reset 후 토픽 잔여 메시지 처리
- Kafka 장애 실험: 브로커 3대, 복제 3, `acks=all`, `min.insync.replicas=2`

## 로드맵

- [x] v1: 동기화 없이 문제 재현
- [x] v2: Atomic, synchronized 비교 및 락 범위 최소화
- [x] v3: 락 없이 CAS(`compareAndSet`)로 해결
- [x] v4: JPA + `@Transactional`만으로는 동시성 보장 안 됨 확인, 트랜잭션 바깥 JVM 락
- [x] v5-1: DB 비관적 락
- [x] v5-2: DB 낙관적 락 (재시도 없음)
- [ ] v5-2: 낙관적 락 재시도, v5-1 품절 빠른 반환
- [x] v5-3: 조건부 UPDATE (`SET stock = stock - 1 WHERE stock > 0`)
- [x] 다중 서버 테스트 환경: Docker 앱 2대 + nginx L4 + k6
- [x] 다중 서버 테스트: v4-2 초과 발급(173건) + 외래키 데드락 재현, v5-1 100건 유지
- [x] v6: Redis 분산 락 (직접 구현 → Redisson → AOP)
- [x] v7 도입: Redis `DECR` 단독 재고 판정
- [x] v7-1: Lua 원자 처리 + INSERT 실패 보상
- [x] v7-2: 1인 1매 (issued Set + `SADD`), 결과 분류(성공/품절/중복/오류)
- [ ] v7: pending 복구, DB 재고 현행화
- [x] 화면에서 서버 2대 대량 발급 (nginx 경유 시뮬레이터)
- [x] v7 리팩토링: Lua 파일 분리, `IssueResult.from`, `CouponRedisKeys`
- [x] v8-1: Kafka 비동기 발급 (Redis 승인 → 컨슈머 DB 반영)
- [x] v8-2: 전송 실패 재전송 (pending 스케줄러, Redisson 락, UNIQUE 멱등), 10% 강제 실패 검증
- [x] v8-2: MySQL 중단 시나리오 검증
- [ ] v8-2: Kafka 중단 시나리오 검증
- [x] v8-3: 컨슈머 실패 재시도·DLT, MySQL 중단 시 DLT 롤백·재전송 어긋남 재현
- [ ] 클라우드 고정 환경 배포·부하 테스트 (AWS 또는 Oracle 상시 무료): 배포 구성 학습 + 대표 버전(v5-1, v6, v7, v8) 처리 시간 비교표. 비용 안전장치(Budgets 알림, 자동 종료, terminate)

## 실행 방법

**서버 1대 (IntelliJ 또는 Maven)**

```bash
docker compose up -d mysql redis kafka     # MySQL (v4~), Redis (v6~), Kafka (v8~)
./mvnw spring-boot:run
```

- `http://localhost:8080` 접속 → 상단 GNB에서 버전 선택 (큰 버전 탭, 마우스오버 시 하위 버전 드롭다운, 큰 버전 클릭은 최신 하위 버전으로)
- 각 버전 화면(`/v1` ~ `/v8-2`)에서 단건 발급, 대량 발급(1만 건, 서버 1대), 분산 대량 발급(nginx 경유, 서버 2대), 초기화 실행
- IDE 앱도 Kafka 컨슈머 그룹에 합류하므로, app1·app2 파티션 배정을 관찰할 때는 IDE 앱을 끄거나 `http://localhost`에서 버튼을 누름
- 기동 완료 시 전 버전 자동 초기화 (DB 재고 100·발급 로그 삭제·Redis 키 정리). DB/Redis는 서버 2대 공유라 한 대만 재시작해도 초기화됨 → 끄려면 `coupon.reset-on-startup=false` (Docker: `COUPON_RESET_ON_STARTUP=false`)
- `ddl-auto: validate`라 테이블은 직접 준비해야 함. v5-2부터 `coupon_stock.version` 컬럼 필요

```sql
ALTER TABLE coupon_stock ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- v8-2: 같은 쿠폰을 같은 회원에게 한 건만 기록 (재전송 중복 방지, user_id NULL은 대상 아님)
ALTER TABLE coupon_publish_log ADD CONSTRAINT uk_coupon_publish_log_stock_user UNIQUE (coupon_stock_id, user_id);
```

**서버 2대 (다중 서버 테스트)**

```bash
./mvnw clean package -Dmaven.test.skip=true    # JDK 21 필요
docker compose up -d --build              # mysql + redis + kafka + app1 + app2 + nginx
docker compose restart nginx              # 앱 컨테이너를 다시 만든 경우 (nginx는 시작 시에만 IP 조회)
```

| 주소 | 대상 |
|---|---|
| `http://localhost` | nginx → app1 / app2 분산 |
| `http://localhost:8081` | app1 직접 |
| `http://localhost:8082` | app2 직접 |

```bash
# v8 토픽 생성 (자동 생성 off, Kafka 컨테이너를 새로 만들면 다시 필요)
docker exec coupon-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --create --topic coupon-issue --partitions 3 --replication-factor 1

# 컨슈머 그룹 배정·LAG 확인
docker exec coupon-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 --describe --group coupon-issue-consumer
```

```bash
# 발급 API (nginx 경유)
curl -X POST http://localhost/v5-1/api/issue

# 두 서버 시뮬레이터 동시 실행 (정확성 확인용)
curl -s -X POST http://localhost:8081/v4/reset -o /dev/null
curl -s -X POST http://localhost:8081/v4/issue-bulk -o /dev/null &
curl -s -X POST http://localhost:8082/v4/issue-bulk -o /dev/null &
wait
```

```bash
# k6 부하 테스트 (compose 네트워크 안에서 nginx로)
curl -s -X POST http://localhost:8081/v5-1/reset -o /dev/null
docker compose run --rm --no-deps -e VERSION=v5-1 k6 run /scripts/issue.js

# HTML 보고서까지 (k6/report-v5-1.html)
docker compose run --rm --no-deps -e VERSION=v5-1 \
  -e K6_WEB_DASHBOARD_EXPORT=/scripts/report-v5-1.html \
  k6 run /scripts/issue.js
```

- `--no-deps`: 없으면 k6의 `depends_on`을 따라 app/nginx 컨테이너가 다시 만들어지고, 앱이 뜨기 전에 요청이 나가 전부 실패할 수 있음

```bash
# v7 Redis 상태 확인
docker exec coupon-redis redis-cli GET coupon:stock:1      # 남은 재고
docker exec coupon-redis redis-cli ZCARD coupon:pending:1  # 결과 미확인 요청 수
docker exec coupon-redis redis-cli SCARD coupon:issued:1   # 발급받은 회원 수 (v7-2)
```

```bash
# v7-2 1인 1매: USERS=회원 수 (user-1~N 랜덤, issue-user API 호출 + result_duplicate 집계)
curl -s -X POST http://localhost:8081/v7-2/reset -o /dev/null
docker compose run --rm --no-deps -e VERSION=v7-2 -e USERS=150 k6 run /scripts/issue.js

# 2장 이상 받은 회원 (0행이어야 정상)
docker exec coupon-mysql mysql -ucoupon -pcoupon coupon -e \
  "SELECT user_id, COUNT(*) FROM coupon_publish_log GROUP BY user_id HAVING COUNT(*) > 1;"
```
