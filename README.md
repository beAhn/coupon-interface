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
- v1~v3: DB 없이 메모리 필드로 재고 관리
- v4~: Spring Data JPA (Hibernate 7), MySQL 8.0 (Docker Compose), HikariCP
- 다중 서버 테스트: Docker 앱 컨테이너 2대, nginx 1.27 (L4 `stream` 로드밸런서), k6
- 예정: Redis (분산 락), 메시지 큐

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
| v5-2 (서버 2대) | k6 1만 건 → nginx, 재시도 없음 | 0 | 100 | 100 | - | ⚠️ 정확하지만 롤백 1,401건 (데드락 1,302 + version 충돌 99) |
| (참고) v5-2에서 `@Version` 미적용 | 락 없는 JPA를 서버 2대로 | 0 | **199** | - | - | ❌ 동시 최대 20 트랜잭션이 겹쳐 읽음 |

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
| 전체 시간 | 8.2초 | 7.3초 (워밍업 후 4.0초) | - |

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
- 결론: 정확성은 지키지만, 한 행에 요청이 몰리면 롤백 비용이 큼. 재시도를 넣는다면 데드락(`CannotAcquireLockException`)도 대상에 포함해야 함

**k6는 Docker 네트워크 안에서 실행**

Mac에서 `localhost:80`으로 보내면 VU 500개가 동시에 연결할 때 Docker Desktop 포트 포워딩 구간에서 `connection reset by peer`가 발생했습니다 (v5-1에서 350건, 앱 로그에는 예외 없음). 같은 테스트를 Docker 네트워크 안에서 실행하면 0건입니다. 그래서 k6를 compose 서비스(`profiles: ["test"]`)로 두고 `docker compose run`으로 실행합니다.

**측정 시 주의**

- 코드를 바꾸면 `./mvnw package` → `docker compose up -d --build` → `docker compose restart nginx`. IntelliJ 실행은 Mac의 별도 앱이라 컨테이너에 반영되지 않음
- 같은 조건으로 3회 이상, 첫 회는 워밍업으로 제외 (v5-1이 7.3초 → 워밍업 후 4.0초)
- k6 `http_req_failed`는 200~399 밖을 모두 실패로 셈 → 409 품절도 포함 (v5-1 정상이 99%)

## 로드맵

- [x] v1: 동기화 없이 문제 재현
- [x] v2: Atomic, synchronized 비교 및 락 범위 최소화
- [x] v3: 락 없이 CAS(`compareAndSet`)로 해결
- [x] v4: JPA + `@Transactional`만으로는 동시성 보장 안 됨 확인, 트랜잭션 바깥 JVM 락
- [x] v5-1: DB 비관적 락
- [x] v5-2: DB 낙관적 락 (재시도 없음)
- [ ] v5-2: 낙관적 락 재시도, v5-1 품절 빠른 반환
- [ ] v5-3: 조건부 UPDATE (`SET stock = stock - 1 WHERE stock > 0`)
- [x] 다중 서버 테스트 환경: Docker 앱 2대 + nginx L4 + k6
- [x] 다중 서버 테스트: v4-2 초과 발급(173건) + 외래키 데드락 재현, v5-1 100건 유지
- [ ] v6: Redis 분산 락
- [ ] v7: 메시지 큐 기반 비동기 발급

## 실행 방법

**서버 1대 (IntelliJ 또는 Maven)**

```bash
docker compose up -d mysql     # MySQL (v4~)
./mvnw spring-boot:run
```

- `http://localhost:8080` 접속 → 버전 선택
- 각 버전 화면(`/v1`, `/v2`, `/v3`, `/v4`, `/v5-1`, `/v5-2`)에서 단건 발급, 대량 발급(1만 건), 초기화 실행
- `ddl-auto: validate`라 테이블은 직접 준비해야 함. v5-2부터 `coupon_stock.version` 컬럼 필요

```sql
ALTER TABLE coupon_stock ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

**서버 2대 (다중 서버 테스트)**

```bash
./mvnw package -Dmaven.test.skip=true    # JDK 21 필요
docker compose up -d --build              # mysql + app1 + app2 + nginx
docker compose restart nginx              # 앱 컨테이너를 다시 만든 경우 (nginx는 시작 시에만 IP 조회)
```

| 주소 | 대상 |
|---|---|
| `http://localhost` | nginx → app1 / app2 분산 |
| `http://localhost:8081` | app1 직접 |
| `http://localhost:8082` | app2 직접 |

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
docker compose run --rm -e VERSION=v5-1 k6 run /scripts/issue.js

# HTML 보고서까지 (k6/report-v5-1.html)
docker compose run --rm -e VERSION=v5-1 \
  -e K6_WEB_DASHBOARD_EXPORT=/scripts/report-v5-1.html \
  k6 run /scripts/issue.js
```
