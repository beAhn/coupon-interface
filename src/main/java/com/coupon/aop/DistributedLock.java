package com.coupon.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();
    long waitTime() default 60;
    long leaseTime() default -1; //-1=watchdog(쥔 동안 자동 연장)
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
