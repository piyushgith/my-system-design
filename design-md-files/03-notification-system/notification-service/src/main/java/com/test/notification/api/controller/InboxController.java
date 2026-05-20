package com.test.notification.api.controller;

import com.test.notification.domain.model.InAppInbox;
import com.test.notification.service.InAppInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{userId}/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InAppInboxService inboxService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getInbox(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<InAppInbox> items = inboxService.getInbox(userId, unreadOnly, page, size);
        long unreadCount = inboxService.getUnreadCount(userId);

        return ResponseEntity.ok(Map.of(
                "items", items.getContent(),
                "page", items.getNumber(),
                "size", items.getSize(),
                "totalPages", items.getTotalPages(),
                "hasMore", items.hasNext(),
                "unreadCount", unreadCount
        ));
    }

    @PatchMapping("/{inboxItemId}")
    public ResponseEntity<Object> markAsRead(
            @PathVariable UUID userId,
            @PathVariable UUID inboxItemId,
            @RequestBody Map<String, Boolean> body) {

        boolean isRead = Boolean.TRUE.equals(body.get("is_read"));
        if (!isRead) return ResponseEntity.badRequest().build();

        return inboxService.markAsRead(userId, inboxItemId)
                .<ResponseEntity<Object>>map(item -> ResponseEntity.ok(Map.of(
                        "inboxItemId", item.getInboxItemId(),
                        "isRead", item.isRead(),
                        "readAt", item.getReadAt()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead(@PathVariable UUID userId) {
        int count = inboxService.markAllAsRead(userId);
        return ResponseEntity.ok(Map.of("markedRead", count));
    }
}
