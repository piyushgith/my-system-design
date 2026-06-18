package com.test.ride.sharing.service.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    @Id
    @Column(length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private int httpStatus;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
