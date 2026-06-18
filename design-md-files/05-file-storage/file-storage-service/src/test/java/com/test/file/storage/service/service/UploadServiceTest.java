package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.ContentBlobRepository;
import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.catalog.StoredFileRepository;
import com.test.file.storage.service.catalog.UploadPart;
import com.test.file.storage.service.catalog.UploadPartRepository;
import com.test.file.storage.service.catalog.UploadSession;
import com.test.file.storage.service.catalog.UploadSessionRepository;
import com.test.file.storage.service.catalog.UploadStatus;
import com.test.file.storage.service.storage.PartETag;
import com.test.file.storage.service.storage.StorageStrategy;
import com.test.file.storage.service.storage.StorageStrategyResolver;
import com.test.file.storage.service.web.error.InvalidUploadStateException;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UploadServiceTest {

    @Mock UploadSessionRepository sessionRepository;
    @Mock UploadPartRepository partRepository;
    @Mock StoredFileRepository fileRepository;
    @Mock ContentBlobRepository blobRepository;
    @Mock StorageStrategyResolver resolver;
    @Mock StorageStrategy storage;

    UploadService uploadService;

    @BeforeEach
    void setUp() {
        uploadService = new UploadService(sessionRepository, partRepository, fileRepository,
                blobRepository, resolver, mock(PresignedUploadFinalizer.class));
        when(resolver.active()).thenReturn(storage);
        when(resolver.byName(anyString())).thenReturn(storage);
        when(storage.name()).thenReturn("local");
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(partRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(blobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private UploadSession inProgress(String id) {
        Instant now = Instant.now();
        UploadSession s = UploadSession.builder()
                .id(id).fileName("big.bin").mimeType("application/octet-stream")
                .storageKey("files/" + id + "/big.bin").providerUploadId("uid").backend("local")
                .ownerId("o").uploadedBytes(0).receivedParts(0).status(UploadStatus.IN_PROGRESS)
                .createdAt(now).expiresAt(now.plusSeconds(3600)).build();
        when(sessionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static InputStream bytes(int n) {
        return new ByteArrayInputStream(new byte[n]);
    }

    @Test
    void initCreatesSessionFromStorageUploadId() {
        when(storage.initiateMultipart(anyString(), any())).thenReturn("provider-uid");

        UploadSession session = uploadService.init("big.bin", "application/octet-stream", "owner1");

        assertThat(session.getProviderUploadId()).isEqualTo("provider-uid");
        assertThat(session.getStatus()).isEqualTo(UploadStatus.IN_PROGRESS);
        assertThat(session.getExpiresAt()).isAfter(session.getCreatedAt());
    }

    @Test
    void uploadPartAddsNewPartAndAdvancesCounters() {
        UploadSession s = inProgress("s1");
        when(storage.uploadPart(anyString(), anyString(), eq(1), any(), anyLong()))
                .thenReturn(new PartETag(1, "etag-1", 100));
        when(partRepository.findBySessionIdAndPartNumber("s1", 1)).thenReturn(Optional.empty());

        uploadService.uploadPart("s1", 1, bytes(100), 100);

        assertThat(s.getReceivedParts()).isEqualTo(1);
        assertThat(s.getUploadedBytes()).isEqualTo(100);
    }

    @Test
    void uploadPartIsIdempotentForRepeatedPartNumber() {
        UploadSession s = inProgress("s1");
        s.setReceivedParts(1);
        s.setUploadedBytes(100);
        UploadPart existing = UploadPart.builder()
                .id("p1").sessionId("s1").partNumber(1).etag("old").sizeBytes(100).build();
        when(partRepository.findBySessionIdAndPartNumber("s1", 1)).thenReturn(Optional.of(existing));
        when(storage.uploadPart(anyString(), anyString(), eq(1), any(), anyLong()))
                .thenReturn(new PartETag(1, "new", 120));

        UploadPart saved = uploadService.uploadPart("s1", 1, bytes(120), 120);

        // No duplicate row: same id reused, etag/size updated, counters reconciled by delta.
        assertThat(saved.getId()).isEqualTo("p1");
        assertThat(saved.getEtag()).isEqualTo("new");
        assertThat(s.getReceivedParts()).isEqualTo(1);
        assertThat(s.getUploadedBytes()).isEqualTo(120);
    }

    @Test
    void uploadPartRejectsNonPositivePartNumber() {
        assertThatThrownBy(() -> uploadService.uploadPart("s1", 0, bytes(1), 1))
                .isInstanceOf(InvalidUploadStateException.class);
    }

    @Test
    void uploadPartRejectsExpiredSession() {
        Instant past = Instant.now().minusSeconds(10);
        UploadSession s = UploadSession.builder()
                .id("s1").backend("local").providerUploadId("uid").storageKey("k")
                .status(UploadStatus.IN_PROGRESS).createdAt(past.minusSeconds(60)).expiresAt(past)
                .build();
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> uploadService.uploadPart("s1", 1, bytes(1), 1))
                .isInstanceOf(InvalidUploadStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void completeAssemblesPartsAndCreatesFile() {
        UploadSession s = inProgress("s1");
        s.setUploadedBytes(200);
        when(partRepository.findBySessionIdOrderByPartNumberAsc("s1")).thenReturn(List.of(
                UploadPart.builder().id("p1").sessionId("s1").partNumber(1).etag("e1").sizeBytes(100).build(),
                UploadPart.builder().id("p2").sessionId("s1").partNumber(2).etag("e2").sizeBytes(100).build()));

        StoredFile file = uploadService.complete("s1");

        ArgumentCaptor<List<PartETag>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storage).completeMultipart(eq("files/s1/big.bin"), eq("uid"), partsCaptor.capture());
        assertThat(partsCaptor.getValue()).hasSize(2);
        assertThat(file.getSizeBytes()).isEqualTo(200);
        assertThat(s.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        verify(partRepository).deleteBySessionId("s1");
    }

    @Test
    void completeIsIdempotentForAlreadyCompletedSession() {
        UploadSession s = inProgress("s1");
        s.setStatus(UploadStatus.COMPLETED);
        s.setFileId("f1");
        StoredFile existing = StoredFile.builder().id("f1").build();
        when(fileRepository.findById("f1")).thenReturn(Optional.of(existing));

        StoredFile result = uploadService.complete("s1");

        assertThat(result.getId()).isEqualTo("f1");
        verify(storage, never()).completeMultipart(anyString(), anyString(), any());
    }

    @Test
    void completeRejectsSessionWithNoParts() {
        inProgress("s1");
        when(partRepository.findBySessionIdOrderByPartNumberAsc("s1")).thenReturn(List.of());

        assertThatThrownBy(() -> uploadService.complete("s1"))
                .isInstanceOf(InvalidUploadStateException.class);
    }

    @Test
    void abortPassesKnownPartsToStorageForCleanup() {
        UploadSession s = inProgress("s1");
        when(partRepository.findBySessionIdOrderByPartNumberAsc("s1")).thenReturn(List.of(
                UploadPart.builder().id("p1").sessionId("s1").partNumber(1).etag("e1").sizeBytes(100).build()));

        uploadService.abort("s1");

        ArgumentCaptor<List<PartETag>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storage).abortMultipart(eq("files/s1/big.bin"), eq("uid"), partsCaptor.capture());
        assertThat(partsCaptor.getValue()).hasSize(1);
        assertThat(s.getStatus()).isEqualTo(UploadStatus.ABORTED);
        verify(partRepository).deleteBySessionId("s1");
    }
}
