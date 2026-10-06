package com.coupon.domain.repository;

import com.coupon.domain.entity.CouponStock;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponStockRepository extends JpaRepository<CouponStock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponStock c where c.id = :id")
    CouponStock findByIdForUpdate(@Param("id") Long id);

}
