package com.test.file.storage.service.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {

    List<UploadSession> findByStatusAndExpiresAtBefore(UploadStatus status, Instant cutoff);
}
