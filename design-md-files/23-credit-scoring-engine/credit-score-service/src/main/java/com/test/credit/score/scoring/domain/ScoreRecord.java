package com.test.credit.score.scoring.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "score_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScoreRecord {

    @Id
    @Column(name = "request_id")
    private String requestId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private int score;

    @Column(name = "raw_pd", nullable = false, precision = 7, scale = 6)
    private BigDecimal rawPd;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_band", nullable = false)
    private ScoreBand scoreBand;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private ProductType productType;

    @Column(name = "feature_snapshot", nullable = false, columnDefinition = "TEXT")
    private String featureSnapshot;

    @Column(name = "reason_codes", nullable = false, columnDefinition = "TEXT")
    private String reasonCodes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScoreSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_role", nullable = false)
    private ModelRole modelRole;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "consent_ref_id")
    private String consentRefId;
}
