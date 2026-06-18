package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.UploadSession;
import com.test.file.storage.service.catalog.UploadSessionRepository;
import com.test.file.storage.service.catalog.UploadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpiredSessionReaperTest {

    @Mock UploadSessionRepository sessionRepository;
    @Mock UploadService uploadService;

    @InjectMocks ExpiredSessionReaper reaper;

    private static UploadSession session(String id) {
        return UploadSession.builder().id(id).status(UploadStatus.IN_PROGRESS).build();
    }

    @Test
    void abortsEveryExpiredSession() {
        when(sessionRepository.findByStatusAndExpiresAtBefore(eq(UploadStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(session("s1"), session("s2")));

        reaper.reapExpiredSessions();

        verify(uploadService).abort("s1");
        verify(uploadService).abort("s2");
    }

    @Test
    void doesNothingWhenNoneExpired() {
        when(sessionRepository.findByStatusAndExpiresAtBefore(eq(UploadStatus.IN_PROGRESS), any()))
                .thenReturn(List.of());

        reaper.reapExpiredSessions();

        verify(uploadService, never()).abort(any());
    }

    @Test
    void oneFailureDoesNotStopRemaining() {
        when(sessionRepository.findByStatusAndExpiresAtBefore(eq(UploadStatus.IN_PROGRESS), any()))
                .thenReturn(List.of(session("bad"), session("good")));
        doThrow(new RuntimeException("boom")).when(uploadService).abort("bad");

        reaper.reapExpiredSessions();

        verify(uploadService).abort("bad");
        verify(uploadService).abort("good");
    }
}
