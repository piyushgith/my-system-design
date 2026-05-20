package com.test.credit.score.model.domain;

import com.test.credit.score.scoring.domain.ModelRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "model_registry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelRegistration {

    @Id
    @Column(name = "model_id")
    private String modelId;

    @Column(name = "model_version", nullable = false, unique = true)
    private String modelVersion;

    @Column(name = "model_type", nullable = false)
    private String modelType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModelRole role;

    @Column(name = "challenger_traffic_pct")
    private int challengerTrafficPct;

    /** Comma-separated product type names. */
    @Column(name = "product_types", nullable = false)
    private String productTypes;

    @Column(name = "s3_model_path", nullable = false)
    private String s3ModelPath;

    /** Comma-separated ordered feature names the model expects. */
    @Column(name = "feature_order", nullable = false, columnDefinition = "TEXT")
    private String featureOrder;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public List<String> featureOrderList() {
        return Arrays.asList(featureOrder.split(","));
    }

    public boolean supportsProduct(String productType) {
        return Arrays.asList(productTypes.split(",")).contains(productType);
    }
}
