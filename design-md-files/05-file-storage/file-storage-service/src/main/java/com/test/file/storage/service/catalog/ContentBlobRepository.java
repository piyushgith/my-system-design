package com.test.file.storage.service.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentBlobRepository extends JpaRepository<ContentBlob, String> {

    Optional<ContentBlob> findByContentHash(String contentHash);
}
