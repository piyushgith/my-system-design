package com.pastebin.paste.infrastructure.persistence;

import com.pastebin.paste.domain.Paste;
import com.pastebin.paste.domain.PasteNotFoundException;
import com.pastebin.shared.ShortKey;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class PasteRepository {

    private final PasteJpaRepository jpaRepository;
    private final PasteMapper mapper;
    private final EntityManager entityManager;

    public PasteRepository(PasteJpaRepository jpaRepository, PasteMapper mapper, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.entityManager = entityManager;
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
        return findByShortKey(shortKey)
                .orElseThrow(() -> new PasteNotFoundException("No paste found with key '" + shortKey.value() + "'"));
    }

    @Transactional
    public long nextShortKeyCounter() {
        Number result = (Number) entityManager
                .createNativeQuery("SELECT nextval('paste.short_key_seq')")
                .getSingleResult();
        return result.longValue();
    }
}
