package com.homeloan.property.repository;

import com.homeloan.property.entity.PropertyValuation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;


@Repository
public interface PropertyValuationRepository extends JpaRepository<PropertyValuation, Long> {

    Optional<PropertyValuation> findByApplicationId(Long applicationId);

    List<PropertyValuation> findByValuationStatus(PropertyValuation.ValuationStatus valuationStatus);

    Optional<PropertyValuation> findBySagaId(String sagaId);

    List<PropertyValuation> findByValuatorName(String valuerName);

    List<PropertyValuation> findByApplicationIdOrderByValuationDateDesc(Long applicationId);


}
