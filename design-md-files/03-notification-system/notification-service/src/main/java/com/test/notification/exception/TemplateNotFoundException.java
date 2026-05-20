package com.test.notification.exception;

public class TemplateNotFoundException extends RuntimeException {
    public TemplateNotFoundException(String templateId, Integer version) {
        super("Template not found: id=" + templateId + (version != null ? " version=" + version : " (latest)"));
    }
}
