package com.test.ride.sharing.service.rating;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ratings")
@Getter
@Setter
@NoArgsConstructor
public class Rating {

    @Id
    private UUID ratingId;

    @Column(nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private String ratedBy;

    @Column(nullable = false)
    private UUID ratedEntityId;

    @Column(nullable = false)
    private int score;

    private String comment;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
