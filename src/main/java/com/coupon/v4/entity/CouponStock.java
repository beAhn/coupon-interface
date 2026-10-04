package com.coupon.v4.entity;

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
