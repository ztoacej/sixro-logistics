package com.sixro.logistics.product.domain.repository;

import com.sixro.logistics.product.domain.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID>,
        JpaSpecificationExecutor<Product> {

    Optional<Product> findByProductIdAndIsDeletedFalse(UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select target from Product target where target.productId = :id and target.isDeleted = false")
    Optional<Product> findActiveForUpdate(@Param("id") UUID id);
}
