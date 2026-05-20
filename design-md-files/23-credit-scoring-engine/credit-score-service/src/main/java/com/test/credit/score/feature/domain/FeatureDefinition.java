package com.test.credit.score.feature.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "feature_definitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeatureDefinition {

    @Id
    @Column(name = "feature_id")
    private String featureId;

    @Column(name = "feature_name", nullable = false, unique = true)
    private String featureName;

    @Column(name = "feature_group", nullable = false)
    private String featureGroup;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "data_source", nullable = false)
    private String dataSource;

    @Column(name = "refresh_frequency_hours", nullable = false)
    private int refreshFrequencyHours;

    @Column(name = "data_type", nullable = false)
    private String dataType;

    @Column(name = "default_value")
    private BigDecimal defaultValue;

    @Column(name = "redis_key_pattern", nullable = false)
    private String redisKeyPattern;

    @Column(name = "is_pii", nullable = false)
    private boolean isPii;

    @Column(name = "regulatory_notes", columnDefinition = "TEXT")
    private String regulatoryNotes;

    public String redisKey(String userId) {
        return redisKeyPattern.replace("{user_id}", userId);
    }

    public double defaultDouble() {
        return defaultValue != null ? defaultValue.doubleValue() : 0.0;
    }
}
