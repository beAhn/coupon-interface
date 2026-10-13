package com.coupon.domain.repository;

import com.coupon.domain.entity.CouponStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    //비관적 락(FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponStock c where c.id = :id")
    Optional<CouponStock> findByIdForUpdate(@Param("id") Long id);


    //조건부 업데이트
    @Modifying
    @Query("update CouponStock c set c.stockQuantity = c.stockQuantity - 1 where c.id = :id and c.stockQuantity > 0")
    int updateCouponStockByConditionalUpdate(@Param("id") Long id);
}
