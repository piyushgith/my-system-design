package com.test.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.model.Template;
import com.test.notification.domain.repository.TemplateRepository;
import com.test.notification.exception.TemplateNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    private static final String CACHE_PREFIX = "tpl:";

    @Transactional(readOnly = true)
    public Template getTemplate(String templateId, Integer version, Channel channel, String locale) {
        String cacheKey = cacheKey(templateId, version, channel, locale);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, Template.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached template key={}", cacheKey);
            }
        }

        Template template = version != null
                ? templateRepository.findByTemplateIdAndVersionAndChannelAndLocale(templateId, version, channel, locale)
                        .orElseThrow(() -> new TemplateNotFoundException(templateId, version))
                : templateRepository.findLatestActive(templateId, channel, locale)
                        .orElseThrow(() -> new TemplateNotFoundException(templateId, null));

        cacheTemplate(cacheKey, template);
        return template;
    }

    @Transactional
    public Template createTemplate(Template template) {
        Template saved = templateRepository.save(template);
        invalidateCache(saved);
        return saved;
    }

    @Transactional
    public void deprecateTemplate(String templateId, Integer version, Channel channel, String locale) {
        Template template = templateRepository
                .findByTemplateIdAndVersionAndChannelAndLocale(templateId, version, channel, locale)
                .orElseThrow(() -> new TemplateNotFoundException(templateId, version));
        template.setActive(false);
        template.setDeprecatedAt(java.time.Instant.now());
        templateRepository.save(template);
        invalidateCache(template);
    }

    public String render(Template template, Map<String, String> variables) {
        String body = template.getBodyText();
        if (variables == null) return body;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            body = body.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return body;
    }

    public String renderSubject(Template template, Map<String, String> variables) {
        if (template.getSubject() == null) return "";
        String subject = template.getSubject();
        if (variables == null) return subject;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            subject = subject.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return subject;
    }

    private void cacheTemplate(String key, Template template) {
        try {
            String json = objectMapper.writeValueAsString(template);
            redisTemplate.opsForValue().set(key, json,
                    Duration.ofHours(props.getCache().getTemplateTtlHours()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache template key={}", key);
        }
    }

    private void invalidateCache(Template t) {
        redisTemplate.delete(cacheKey(t.getTemplateId(), t.getVersion(), t.getChannel(), t.getLocale()));
    }

    private String cacheKey(String templateId, Integer version, Channel channel, String locale) {
        return CACHE_PREFIX + templateId + ":" + version + ":" + channel + ":" + locale;
    }
}
