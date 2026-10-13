package com.coupon.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;

@Getter
@Entity
public class CouponStock extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int stockQuantity;

    // v5-2 낙관적 락용. v4-1(JPA+단순 트랜잭션 초과 발급 테스트) 영향: 초과 발급 대신 예외 발생
//    @Version
//    private Long version;

    public boolean decrease(){
        if (stockQuantity > 0){
            stockQuantity--;
            return true;
        }

        return false;
    }

    public void reset() {
        stockQuantity = 100;
    }
}
