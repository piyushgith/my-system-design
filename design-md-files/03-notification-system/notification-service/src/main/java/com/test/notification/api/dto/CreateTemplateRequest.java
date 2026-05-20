package com.test.notification.api.dto;

import com.test.notification.domain.enums.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter @Setter
public class CreateTemplateRequest {

    @NotBlank
    private String templateId;

    @NotNull
    private Channel channel;

    private String locale = "en-US";

    private String subject;

    @NotBlank
    private String bodyText;

    private String bodyHtml;

    private String pushTitle;

    private String pushBody;

    private Map<String, String> variablesSchema;

    private String createdBy;
}
