package com.test.file.storage.service.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadPartRepository extends JpaRepository<UploadPart, String> {

    List<UploadPart> findBySessionIdOrderByPartNumberAsc(String sessionId);

    Optional<UploadPart> findBySessionIdAndPartNumber(String sessionId, int partNumber);

    void deleteBySessionId(String sessionId);
}
