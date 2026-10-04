package com.coupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
public class CouponInterfaceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CouponInterfaceApplication.class, args);
	}

}
