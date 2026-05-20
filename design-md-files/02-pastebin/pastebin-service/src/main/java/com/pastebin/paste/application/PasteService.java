package com.pastebin.paste.application;

import com.pastebin.identity.application.PasswordService;
import com.pastebin.paste.application.event.PasteCreatedEvent;
import com.pastebin.paste.application.event.PasteDeletedEvent;
import com.pastebin.paste.domain.ContentHasher;
import com.pastebin.paste.domain.ContentRouter;
import com.pastebin.paste.domain.ContentRoutingDecision;
import com.pastebin.paste.domain.DomainException;
import com.pastebin.paste.domain.Paste;
import com.pastebin.paste.domain.PasteGoneException;
import com.pastebin.paste.domain.PasteNotAccessibleException;
import com.pastebin.paste.domain.PasteNotFoundException;
import com.pastebin.paste.domain.ShortKeyGenerator;
import com.pastebin.paste.infrastructure.cache.IdempotencyStore;
import com.pastebin.paste.infrastructure.cache.PasteCache;
import com.pastebin.paste.infrastructure.persistence.ExpiryScheduleEntity;
import com.pastebin.paste.infrastructure.persistence.ExpiryScheduleJpaRepository;
import com.pastebin.paste.infrastructure.persistence.PasteEntity;
import com.pastebin.paste.infrastructure.persistence.PasteJpaRepository;
import com.pastebin.paste.infrastructure.persistence.PasteMapper;
import com.pastebin.paste.infrastructure.persistence.PasteRepository;
import com.pastebin.paste.infrastructure.storage.ContentStorageException;
import com.pastebin.paste.infrastructure.storage.S3ContentStorage;
import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ContentType;
import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.ExpiryPolicy;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;
import com.pastebin.shared.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasteService {

    private final PasteRepository pasteRepository;
    private final PasteJpaRepository pasteJpaRepository;
    private final PasteMapper pasteMapper;
    private final ExpiryScheduleJpaRepository expiryScheduleRepository;
    private final ShortKeyGenerator shortKeyGenerator;
    private final ContentRouter contentRouter;
    private final S3ContentStorage s3ContentStorage;
    private final PasteCache pasteCache;
    private final IdempotencyStore idempotencyStore;
    private final PasswordService passwordService;
    private final ApplicationEventPublisher eventPublisher;
    private final PastebinProperties properties;
    private final ObjectMapper objectMapper;
    private final Timer createTimer;
    private final Timer readTimer;
    private final MeterRegistry meterRegistry;

    public PasteService(PasteRepository pasteRepository,
                        PasteJpaRepository pasteJpaRepository,
                        PasteMapper pasteMapper,
                        ExpiryScheduleJpaRepository expiryScheduleRepository,
                        ShortKeyGenerator shortKeyGenerator,
                        ContentRouter contentRouter,
                        S3ContentStorage s3ContentStorage,
                        PasteCache pasteCache,
                        IdempotencyStore idempotencyStore,
                        PasswordService passwordService,
                        ApplicationEventPublisher eventPublisher,
                        PastebinProperties properties,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.pasteRepository = pasteRepository;
        this.pasteJpaRepository = pasteJpaRepository;
        this.pasteMapper = pasteMapper;
        this.expiryScheduleRepository = expiryScheduleRepository;
        this.shortKeyGenerator = shortKeyGenerator;
        this.contentRouter = contentRouter;
        this.s3ContentStorage = s3ContentStorage;
        this.pasteCache = pasteCache;
        this.idempotencyStore = idempotencyStore;
        this.passwordService = passwordService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.createTimer = Timer.builder("paste.create.latency").register(meterRegistry);
        this.readTimer = Timer.builder("paste.read.latency").register(meterRegistry);
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public CreatePasteResult createPaste(CreatePasteCommand command, Optional<UserId> ownerId) {
        return createTimer.record(() -> doCreatePaste(command, ownerId));
    }

    private CreatePasteResult doCreatePaste(CreatePasteCommand command, Optional<UserId> ownerId) {
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            Optional<CreatePasteResult> cached = idempotencyStore.get(command.idempotencyKey(), CreatePasteResult.class);
            if (cached.isPresent()) {
                CreatePasteResult result = cached.get();
                return new CreatePasteResult(result.id(), result.shortKey(), result.url(), result.rawUrl(),
                        result.language(), result.expiresAt(), result.accessLevel(), result.createdAt(),
                        result.size(), true);
            }
            if (!idempotencyStore.tryAcquire(command.idempotencyKey())) {
                return idempotencyStore.get(command.idempotencyKey(), CreatePasteResult.class)
                        .orElseThrow(() -> new DomainException("Duplicate paste creation in progress"));
            }
        }

        Instant now = Instant.now();
        PasteId pasteId = PasteId.generate();
        ShortKey shortKey = shortKeyGenerator.nextKey();
        String contentHash = ContentHasher.sha256(command.content());
        byte[] contentBytes = command.content().getBytes(StandardCharsets.UTF_8);

        String s3Key = s3ContentStorage.buildKey(pasteId.toString());
        ContentRoutingDecision routing = contentRouter.route(command.content(), s3Key);

        if (routing.contentType() == ContentType.S3) {
            try {
                s3ContentStorage.upload(s3Key, command.content());
            } catch (Exception e) {
                throw new ContentStorageException("Failed to upload paste content", e);
            }
        }

        ExpiryPolicy expiryPolicy = command.expiryPolicy() != null ? command.expiryPolicy() : ExpiryPolicy.ONE_WEEK;
        AccessLevel accessLevel = command.accessLevel() != null ? command.accessLevel() : AccessLevel.PUBLIC;
        String passwordHash = command.password() != null && !command.password().isBlank()
                ? passwordService.hash(command.password()) : null;

        Paste paste = Paste.create(
                pasteId,
                shortKey,
                command.title(),
                command.language() != null ? command.language() : "plaintext",
                routing.contentType(),
                routing.inlineContent(),
                routing.s3Key(),
                contentBytes.length,
                contentHash,
                expiryPolicy,
                accessLevel,
                passwordHash,
                ownerId.orElse(null),
                now,
                properties.content().maxSizeBytes()
        );

        pasteRepository.save(paste);
        scheduleExpiry(paste);

        CreatePasteResult result = toCreateResult(paste, false);
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            idempotencyStore.store(command.idempotencyKey(), result);
        }

        eventPublisher.publishEvent(new PasteCreatedEvent(paste.getId(), paste.getExpiresAt()));
        return result;
    }

    @Transactional(readOnly = true)
    public PasteView getPaste(String key, Optional<UserId> requesterId, Optional<String> password) {
        return readTimer.record(() -> doGetPaste(key, requesterId, password));
    }

    private PasteView doGetPaste(String key, Optional<UserId> requesterId, Optional<String> password) {
        Optional<PasteView> cached = pasteCache.get(key);
        if (cached.isPresent()) {
            meterRegistry.counter("paste.read.cache_hit").increment();
            // cache invariant: only PUBLIC + !passwordProtected pastes are stored
            return cached.get();
        }
        meterRegistry.counter("paste.read.cache_miss").increment();

        Paste paste = pasteRepository.findByShortKey(new ShortKey(key))
                .orElseThrow(() -> {
                    pasteCache.putNegative(key);
                    return new PasteNotFoundException("No paste found with key '" + key + "'");
                });

        if (paste.isDeleted()) {
            throw new PasteGoneException("Paste has been deleted");
        }
        if (paste.isExpired(Instant.now())) {
            throw new PasteGoneException("Paste has expired");
        }

        boolean passwordVerified = !paste.requiresPassword()
                || password.filter(p -> passwordService.matches(p, paste.getPasswordHash())).isPresent();
        paste.assertReadable(requesterId, passwordVerified);

        String content = resolveContent(paste);
        PasteView view = toView(paste, content);
        if (paste.getAccessLevel() == AccessLevel.PUBLIC && !paste.requiresPassword()) {
            pasteCache.put(key, view, paste.getExpiresAt());
        }
        return view;
    }

    @Transactional(readOnly = true)
    public String getRawContent(String key, Optional<UserId> requesterId, Optional<String> password) {
        PasteView view = getPaste(key, requesterId, password);
        return view.content();
    }

    @Transactional
    public void deletePaste(String key, UserId ownerId) {
        Paste paste = pasteRepository.getByShortKey(new ShortKey(key));
        if (paste.isDeleted()) {
            return;
        }
        if (paste.getOwnerId() == null || !paste.getOwnerId().equals(ownerId)) {
            throw new PasteNotAccessibleException("Only the paste owner can delete this paste");
        }
        paste.softDelete(DeletionReason.USER_REQUESTED, Instant.now());
        pasteRepository.save(paste);
        pasteCache.evict(key);
        eventPublisher.publishEvent(new PasteDeletedEvent(
                paste.getId(),
                paste.getShortKey(),
                paste.getContentS3Key(),
                DeletionReason.USER_REQUESTED
        ));
    }

    @Transactional(readOnly = true)
    public PasteListResult listUserPastes(UserId ownerId, String cursor, int limit, boolean includeExpired) {
        int pageSize = Math.min(Math.max(limit, 1), 100);
        Instant cursorCreatedAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = decodeCursor(cursor);
            cursorCreatedAt = decoded.createdAt();
            cursorId = decoded.id();
        }

        List<PasteEntity> entities = pasteJpaRepository.findUserPastes(
                ownerId.value(),
                Instant.now(),
                includeExpired,
                cursorCreatedAt,
                cursorId,
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasMore = entities.size() > pageSize;
        List<PasteEntity> page = hasMore ? entities.subList(0, pageSize) : entities;
        List<PasteSummary> items = page.stream().map(this::toSummary).toList();
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;
        return new PasteListResult(items, nextCursor, hasMore);
    }

    @Transactional
    public void markExpired(PasteId pasteId, DeletionReason reason) {
        Paste paste = pasteRepository.findByShortKey(loadShortKey(pasteId))
                .orElseThrow(() -> new PasteNotFoundException("Paste not found for expiry: " + pasteId));
        if (paste.isDeleted()) {
            return;
        }
        paste.softDelete(reason, Instant.now());
        pasteRepository.save(paste);
        pasteCache.evict(paste.getShortKey().value());
        eventPublisher.publishEvent(new PasteDeletedEvent(
                paste.getId(),
                paste.getShortKey(),
                paste.getContentS3Key(),
                reason
        ));
    }

    private ShortKey loadShortKey(PasteId pasteId) {
        return pasteJpaRepository.findById(pasteId.value())
                .map(entity -> new ShortKey(entity.getShortKey()))
                .orElseThrow(() -> new PasteNotFoundException("Paste not found: " + pasteId));
    }

    private void scheduleExpiry(Paste paste) {
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

    private String resolveContent(Paste paste) {
        if (paste.getContentType() == ContentType.INLINE) {
            return paste.getContentInline();
        }
        return s3ContentStorage.download(paste.getContentS3Key());
    }

    private CreatePasteResult toCreateResult(Paste paste, boolean idempotentReplay) {
        String baseUrl = properties.baseUrl();
        return new CreatePasteResult(
                paste.getId().toString(),
                paste.getShortKey().value(),
                baseUrl + "/p/" + paste.getShortKey().value(),
                baseUrl + "/raw/" + paste.getShortKey().value(),
                paste.getLanguage(),
                paste.getExpiresAt(),
                paste.getAccessLevel(),
                paste.getCreatedAt(),
                paste.getContentSize(),
                idempotentReplay
        );
    }

    private PasteView toView(Paste paste, String content) {
        return new PasteView(
                paste.getId().toString(),
                paste.getShortKey().value(),
                paste.getTitle(),
                content,
                paste.getLanguage(),
                paste.getContentType(),
                paste.getContentS3Key(),
                paste.getContentSize(),
                inferExpiryPolicy(paste),
                paste.getExpiresAt(),
                paste.getAccessLevel(),
                paste.requiresPassword(),
                paste.getViewCount(),
                paste.getCreatedAt(),
                null
        );
    }

    private PasteSummary toSummary(PasteEntity entity) {
        return new PasteSummary(
                entity.getShortKey(),
                entity.getTitle(),
                entity.getLanguage(),
                entity.getAccessLevel(),
                entity.getViewCount(),
                entity.getContentSize(),
                entity.getExpiresAt(),
                entity.getCreatedAt()
        );
    }

    private ExpiryPolicy inferExpiryPolicy(Paste paste) {
        if (paste.getExpiresAt() == null) return ExpiryPolicy.NEVER;
        long seconds = paste.getExpiresAt().getEpochSecond() - paste.getCreatedAt().getEpochSecond();
        if (seconds <= 3600) return ExpiryPolicy.ONE_HOUR;
        if (seconds <= 86400) return ExpiryPolicy.ONE_DAY;
        if (seconds <= 604800) return ExpiryPolicy.ONE_WEEK;
        return ExpiryPolicy.ONE_MONTH;
    }

    private record Cursor(Instant createdAt, UUID id) {
    }

    private String encodeCursor(Instant createdAt, UUID id) {
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("c", createdAt.toString(), "i", id.toString()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new DomainException("Failed to encode pagination cursor");
        }
    }

    private Cursor decodeCursor(String cursor) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(json, Map.class);
            return new Cursor(Instant.parse(map.get("c")), UUID.fromString(map.get("i")));
        } catch (Exception e) {
            throw new DomainException("Invalid pagination cursor");
        }
    }
}
