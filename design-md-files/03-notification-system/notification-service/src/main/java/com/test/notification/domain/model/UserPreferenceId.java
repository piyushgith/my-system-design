package com.test.notification.domain.model;

import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class UserPreferenceId implements Serializable {
    private UUID userId;
    private Channel channel;
    private Category category;
}
