package com.coupon.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Getter
@Entity
public class CouponPublishLog extends BaseEntity {

    public CouponPublishLog() {

    }

    public CouponPublishLog (CouponStock couponStock) {
        //쿠폰NO 랜덤생성
        this.couponNo = generateCouponNo();
        this.couponStock = couponStock;
    }

    // CPN-yyyyMMdd-UUID앞23자리 (총 36자, 컬럼 길이에 맞춤)
    private static String generateCouponNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        return "CPN-" + date + "-" + random;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String couponNo; // CPN-날짜-UUID

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="coupon_stock_id", nullable = false)
    private CouponStock couponStock;

    @Column(length = 50)
    private String userId; // 선택 (1인 1매 실험용)

}
