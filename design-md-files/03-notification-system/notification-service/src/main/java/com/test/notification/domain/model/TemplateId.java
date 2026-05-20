package com.test.notification.domain.model;

import com.test.notification.domain.enums.Channel;
import lombok.*;

import java.io.Serializable;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class TemplateId implements Serializable {
    private String templateId;
    private Integer version;
    private Channel channel;
    private String locale;
}
