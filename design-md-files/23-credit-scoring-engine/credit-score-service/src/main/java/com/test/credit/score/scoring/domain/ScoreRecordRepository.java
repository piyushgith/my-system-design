package com.test.credit.score.scoring.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ScoreRecordRepository extends JpaRepository<ScoreRecord, String> {

    Page<ScoreRecord> findByUserIdOrderByComputedAtDesc(String userId, Pageable pageable);

    Optional<ScoreRecord> findTopByUserIdAndProductTypeOrderByComputedAtDesc(String userId, ProductType productType);

    Page<ScoreRecord> findByUserIdAndComputedAtBetweenOrderByComputedAtDesc(
            String userId, Instant from, Instant to, Pageable pageable);
}
