package com.sixro.logistics.company.domain.repository;

import com.sixro.logistics.company.domain.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository
        extends JpaRepository<Company, UUID>,
        JpaSpecificationExecutor<Company> {

    Optional<Company> findByCompanyIdAndIsDeletedFalse(UUID companyId);

    boolean existsByBusinessNumberAndIsDeletedFalse(String businessNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select target from Company target where target.companyId = :id and target.isDeleted = false")
    Optional<Company> findActiveForUpdate(@Param("id") UUID id);
}
