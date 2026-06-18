package com.test.file.storage.service.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A single uploaded part of a multipart {@link UploadSession}. */
@Entity
@Table(name = "upload_part", uniqueConstraints = @UniqueConstraint(columnNames = {"sessionId", "partNumber"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadPart {

    @Id
    private String id;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private int partNumber;

    @Column(nullable = false)
    private String etag;

    @Column(nullable = false)
    private long sizeBytes;
}
