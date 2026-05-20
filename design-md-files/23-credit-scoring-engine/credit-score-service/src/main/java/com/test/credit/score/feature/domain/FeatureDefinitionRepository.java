package com.test.credit.score.feature.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureDefinitionRepository extends JpaRepository<FeatureDefinition, String> {

    List<FeatureDefinition> findByFeatureGroup(String featureGroup);
}
