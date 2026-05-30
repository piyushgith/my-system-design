package com.homeloan.creditcheck.repository;

import com.homeloan.creditcheck.entity.CreditCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface CreditCheckRepository extends JpaRepository<CreditCheck, Long> {

    Optional<CreditCheck> findBySagaId(String sagaId);

    Optional<CreditCheck> findByApplicationId(Long applicationId);

    List<CreditCheck> findByApplicationIdOrderByCheckDateDesc(Long applicationId);

    List<CreditCheck> findByCreditStatus(String creditStatus);

}
