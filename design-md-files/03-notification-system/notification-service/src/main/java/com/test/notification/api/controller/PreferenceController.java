package com.test.notification.api.controller;

import com.test.notification.api.dto.UpdatePreferencesRequest;
import com.test.notification.domain.model.UserNotificationPreference;
import com.test.notification.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users/{userId}/notification-preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getPreferences(@PathVariable UUID userId) {
        List<UserNotificationPreference> prefs = preferenceService.getPreferences(userId);
        List<Map<String, Object>> prefList = prefs.stream()
                .map(p -> Map.<String, Object>of(
                        "channel", p.getChannel(),
                        "category", p.getCategory(),
                        "optedIn", p.isOptedIn()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("userId", userId, "preferences", prefList));
    }

    @PatchMapping
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @PathVariable UUID userId,
            @RequestBody UpdatePreferencesRequest req) {

        List<UserNotificationPreference> updates = req.getPreferences().stream()
                .map(entry -> {
                    UserNotificationPreference pref = new UserNotificationPreference();
                    pref.setUserId(userId);
                    pref.setChannel(entry.getChannel());
                    pref.setCategory(entry.getCategory());
                    pref.setOptedIn(entry.isOptedIn());
                    if (req.getQuietHours() != null) {
                        pref.setQuietHoursStart(req.getQuietHours().getStart());
                        pref.setQuietHoursEnd(req.getQuietHours().getEnd());
                        pref.setTimezone(req.getQuietHours().getTimezone());
                    }
                    return pref;
                })
                .collect(Collectors.toList());

        List<UserNotificationPreference> saved = preferenceService.upsertPreferences(userId, updates);
        List<String> updated = saved.stream()
                .map(p -> p.getChannel() + ":" + p.getCategory())
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("updated", updated, "ignored", List.of()));
    }
}
