package com.coupon.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {
    private final RedissonClient redissonClient;
    private final AopForTransaction aopForTransaction;


    //distributedLock 커스텀 어노테이션에 AOP Around 적용
    @Around("@annotation(distributedLock)")
    public Object lock(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        //레디슨 lock 객체 생성
        RLock lock = redissonClient.getLock(distributedLock.key());
        boolean acquired = false;

        try {
            log.info("Redisson 락 시도");
            //락시도
            acquired = lock.tryLock(distributedLock.waitTime(), distributedLock.leaseTime(), distributedLock.timeUnit());

            if (!acquired) {
                throw new IllegalStateException("락 대기 시간 초과: " + distributedLock.key());
            }

            //락시도 성공 후 새 트랜잭션에서 실행(트랜잭션은 프록시를 거쳐야 실행되기 때문에 개별 인스턴스로 빼서 호출)
            return aopForTransaction.proceed(joinPoint);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("락 대기 중 인터럽트", e);
        }finally {
            if(acquired && lock.isHeldByCurrentThread()) {
                log.info("Redisson 락 해제");
                lock.unlock();
            }

        }
    }
}
