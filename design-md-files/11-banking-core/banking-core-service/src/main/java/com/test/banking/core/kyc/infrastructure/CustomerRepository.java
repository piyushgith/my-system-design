package com.test.banking.core.kyc.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {

    boolean existsByPanHash(String panHash);

    Optional<CustomerEntity> findByCifId(String cifId);
}
