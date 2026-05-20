package com.test.notification.domain.repository;

import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.model.Template;
import com.test.notification.domain.model.TemplateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, TemplateId> {

    @Query("SELECT t FROM Template t WHERE t.templateId = :templateId AND t.channel = :channel " +
           "AND t.locale = :locale AND t.isActive = true ORDER BY t.version DESC")
    Optional<Template> findLatestActive(String templateId, Channel channel, String locale);

    Optional<Template> findByTemplateIdAndVersionAndChannelAndLocale(
            String templateId, Integer version, Channel channel, String locale);
}
