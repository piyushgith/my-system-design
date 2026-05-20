package com.test.notification.domain.model;

import com.test.notification.domain.enums.Channel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(
    name = "templates",
    indexes = {
        @Index(name = "idx_template_active", columnList = "template_id, channel, locale")
    }
)
@IdClass(TemplateId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Template {

    @Id
    @Column(name = "template_id", nullable = false, length = 100)
    private String templateId;

    @Id
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Id
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String locale = "en-US";

    @Column(length = 500)
    private String subject;

    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "push_title", length = 100)
    private String pushTitle;

    @Column(name = "push_body", length = 255)
    private String pushBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables_schema", columnDefinition = "TEXT")
    private Map<String, String> variablesSchema;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "deprecated_at")
    private Instant deprecatedAt;
}
