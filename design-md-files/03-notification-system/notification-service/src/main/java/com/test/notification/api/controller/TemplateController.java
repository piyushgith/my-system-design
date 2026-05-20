package com.test.notification.api.controller;

import com.test.notification.api.dto.CreateTemplateRequest;
import com.test.notification.domain.model.Template;
import com.test.notification.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping
    public ResponseEntity<Template> createTemplate(@Valid @RequestBody CreateTemplateRequest req) {
        Template template = Template.builder()
                .templateId(req.getTemplateId())
                .channel(req.getChannel())
                .locale(req.getLocale() != null ? req.getLocale() : "en-US")
                .subject(req.getSubject())
                .bodyText(req.getBodyText())
                .bodyHtml(req.getBodyHtml())
                .pushTitle(req.getPushTitle())
                .pushBody(req.getPushBody())
                .variablesSchema(req.getVariablesSchema())
                .createdBy(req.getCreatedBy())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.createTemplate(template));
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<Template> getTemplate(
            @PathVariable String templateId,
            @RequestParam(defaultValue = "EMAIL") com.test.notification.domain.enums.Channel channel,
            @RequestParam(defaultValue = "en-US") String locale) {

        return ResponseEntity.ok(templateService.getTemplate(templateId, null, channel, locale));
    }

    @DeleteMapping("/{templateId}/versions/{version}")
    public ResponseEntity<Void> deprecateTemplate(
            @PathVariable String templateId,
            @PathVariable Integer version,
            @RequestParam(defaultValue = "EMAIL") com.test.notification.domain.enums.Channel channel,
            @RequestParam(defaultValue = "en-US") String locale) {

        templateService.deprecateTemplate(templateId, version, channel, locale);
        return ResponseEntity.noContent().build();
    }
}
