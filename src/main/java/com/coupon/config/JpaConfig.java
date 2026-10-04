package com.coupon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

// JPA Auditing 설정 (등록/수정 일시, 등록/수정자 자동 입력)
@Configuration
@EnableJpaAuditing
public class JpaConfig {

    // 등록/수정자: 로그인 없으니 고정값
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("system");
    }
}
