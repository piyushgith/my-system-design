package com.pastebin.paste.infrastructure.persistence;

import com.pastebin.paste.domain.Paste;
import com.pastebin.paste.domain.PasteNotFoundException;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;
import com.pastebin.shared.UserId;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PasteRepository {

    private final PasteJpaRepository jpaRepository;
    private final PasteMapper mapper;
    private final EntityManager entityManager;
    private final ExpiryScheduleJpaRepository expiryScheduleRepository;

    public PasteRepository(PasteJpaRepository jpaRepository,
                           PasteMapper mapper,
                           EntityManager entityManager,
                           ExpiryScheduleJpaRepository expiryScheduleRepository) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.expiryScheduleRepository = expiryScheduleRepository;
    }

    @Transactional
    public Paste save(Paste paste) {
        PasteEntity saved = jpaRepository.save(mapper.toEntity(paste));
        return mapper.toDomain(saved);
    }

    @Transactional(readOnly = true)
    public Optional<Paste> findByShortKey(ShortKey shortKey) {
        return jpaRepository.findByShortKeyIncludingDeleted(shortKey.value()).map(mapper::toDomain);
    }

    @Transactional(readOnly = true)
    public Paste getByShortKey(ShortKey shortKey) {
        return jpaRepository.findByShortKeyIncludingDeleted(shortKey.value())
                .map(mapper::toDomain)
                .orElseThrow(() -> new PasteNotFoundException("No paste found with key '" + shortKey.value() + "'"));
    }

    @Transactional(readOnly = true)
    public Optional<Paste> findById(PasteId id) {
        return jpaRepository.findById(id.value()).map(mapper::toDomain);
    }

    @Transactional(readOnly = true)
    public List<Paste> findByOwner(UserId ownerId, Instant now, boolean includeExpired,
                                   Instant cursorCreatedAt, UUID cursorId, int pageSize) {
        return jpaRepository.findUserPastes(
                        ownerId.value(), now, includeExpired,
                        cursorCreatedAt, cursorId,
                        PageRequest.of(0, pageSize))
                .stream().map(mapper::toDomain).toList();
    }

    @Transactional
    public void scheduleExpiry(Paste paste) {
        if (paste.getExpiresAt() == null) {
            return;
        }
        ExpiryScheduleEntity schedule = new ExpiryScheduleEntity();
        schedule.setPasteId(paste.getId().value());
        schedule.setExpiresAt(paste.getExpiresAt());
        schedule.setProcessed(false);
        schedule.setCreatedAt(Instant.now());
        expiryScheduleRepository.save(schedule);
    }

    @Transactional
    public long nextShortKeyCounter() {
        Number result = (Number) entityManager
                .createNativeQuery("SELECT nextval('paste.short_key_seq')")
                .getSingleResult();
        return result.longValue();
    }
}
