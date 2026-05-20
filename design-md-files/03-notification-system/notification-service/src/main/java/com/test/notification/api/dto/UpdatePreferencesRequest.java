package com.test.notification.api.dto;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;

@Getter @Setter
public class UpdatePreferencesRequest {

    private List<PreferenceEntry> preferences;
    private QuietHours quietHours;

    @Getter @Setter
    public static class PreferenceEntry {
        private Channel channel;
        private Category category;
        private boolean optedIn;
    }

    @Getter @Setter
    public static class QuietHours {
        private LocalTime start;
        private LocalTime end;
        private String timezone;
    }
}
