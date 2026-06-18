package com.test.file.storage.service.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
}
