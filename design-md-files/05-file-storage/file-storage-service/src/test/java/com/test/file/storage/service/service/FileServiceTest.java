package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.ContentBlob;
import com.test.file.storage.service.catalog.ContentBlobRepository;
import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.catalog.StoredFileRepository;
import com.test.file.storage.service.storage.StorageStrategy;
import com.test.file.storage.service.storage.StorageStrategyResolver;
import com.test.file.storage.service.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceTest {

    @Mock StoredFileRepository fileRepository;
    @Mock ContentBlobRepository blobRepository;
    @Mock StorageStrategyResolver resolver;
    @Mock StorageStrategy storage;

    FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(fileRepository, blobRepository, resolver, new PresignedUrlCache());
        when(resolver.active()).thenReturn(storage);
        when(resolver.byName(anyString())).thenReturn(storage);
        when(storage.name()).thenReturn("local");
        when(blobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static InputStream content(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadStoresBytesAndCreatesBlobForNewContent() {
        when(blobRepository.findByContentHash(anyString())).thenReturn(Optional.empty());

        StoredFile result = fileService.upload("a.txt", "text/plain", content("hello"), "owner1");

        verify(storage).store(anyString(), any(), anyLong(), eq("text/plain"));
        ArgumentCaptor<ContentBlob> blobCaptor = ArgumentCaptor.forClass(ContentBlob.class);
        verify(blobRepository).save(blobCaptor.capture());
        assertThat(blobCaptor.getValue().getRefCount()).isEqualTo(1);
        assertThat(result.getOwnerId()).isEqualTo("owner1");
        assertThat(result.getSizeBytes()).isEqualTo(5);
    }

    @Test
    void uploadDeduplicatesAgainstExistingBlob() {
        ContentBlob existing = ContentBlob.builder()
                .storageKey("blobs/x").sizeBytes(5).backend("local").refCount(1)
                .createdAt(Instant.now()).build();
        when(blobRepository.findByContentHash(anyString())).thenReturn(Optional.of(existing));

        fileService.upload("a.txt", "text/plain", content("hello"), "owner1");

        // No bytes re-written; existing blob's ref count bumped.
        verify(storage, never()).store(anyString(), any(), anyLong(), anyString());
        assertThat(existing.getRefCount()).isEqualTo(2);
    }

    @Test
    void uploadCompensatesByDeletingBytesWhenDbSaveFails() {
        when(blobRepository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(fileRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> fileService.upload("a.txt", "text/plain", content("hello"), "o"))
                .isInstanceOf(RuntimeException.class);

        // Orphaned object removed so a failed transaction doesn't leak bytes.
        verify(storage).delete(anyString());
    }

    @Test
    void deleteRemovesPhysicalObjectWhenLastReference() {
        StoredFile file = StoredFile.builder().id("f1").contentHash("h1").backend("local").build();
        ContentBlob blob = ContentBlob.builder()
                .contentHash("h1").storageKey("blobs/h1").backend("local").refCount(1).build();
        when(fileRepository.findById("f1")).thenReturn(Optional.of(file));
        when(blobRepository.findByContentHash("h1")).thenReturn(Optional.of(blob));

        fileService.delete("f1");

        verify(storage).delete("blobs/h1");
        verify(blobRepository).delete(blob);
        verify(fileRepository).delete(file);
    }

    @Test
    void deleteKeepsObjectWhenOtherReferencesRemain() {
        StoredFile file = StoredFile.builder().id("f1").contentHash("h1").backend("local").build();
        ContentBlob blob = ContentBlob.builder()
                .contentHash("h1").storageKey("blobs/h1").backend("local").refCount(2).build();
        when(fileRepository.findById("f1")).thenReturn(Optional.of(file));
        when(blobRepository.findByContentHash("h1")).thenReturn(Optional.of(blob));

        fileService.delete("f1");

        verify(storage, never()).delete(anyString());
        assertThat(blob.getRefCount()).isEqualTo(1);
        verify(fileRepository).delete(file);
    }

    @Test
    void getThrowsWhenFileMissing() {
        when(fileRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
