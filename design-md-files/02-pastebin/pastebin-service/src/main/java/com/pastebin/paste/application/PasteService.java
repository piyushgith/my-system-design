package com.pastebin.paste.application;

import com.pastebin.identity.application.PasswordService;
import com.pastebin.paste.application.event.PasteCreatedEvent;
import com.pastebin.paste.application.event.PasteDeletedEvent;
import com.pastebin.paste.domain.ContentHasher;
import com.pastebin.paste.domain.ContentRouter;
import com.pastebin.paste.domain.ContentRoutingDecision;
import com.pastebin.paste.domain.DomainException;
import com.pastebin.paste.domain.Paste;
import com.pastebin.paste.domain.PasteNotFoundException;
import com.pastebin.paste.domain.ShortKeyGenerator;
import com.pastebin.paste.infrastructure.cache.IdempotencyStore;
import com.pastebin.paste.infrastructure.cache.PasteCache;
import com.pastebin.paste.infrastructure.persistence.PasteRepository;
import com.pastebin.paste.infrastructure.storage.ContentStorage;
import com.pastebin.paste.infrastructure.storage.ContentStorageException;
import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.ExpiryPolicy;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;
import com.pastebin.shared.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasteService {

    private final PasteRepository pasteRepository;
    private final ShortKeyGenerator shortKeyGenerator;
    private final ContentRouter contentRouter;
    private final ContentStorage contentStorage;
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
                        ShortKeyGenerator shortKeyGenerator,
                        ContentRouter contentRouter,
                        ContentStorage contentStorage,
                        PasteCache pasteCache,
                        IdempotencyStore idempotencyStore,
                        PasswordService passwordService,
                        ApplicationEventPublisher eventPublisher,
                        PastebinProperties properties,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.pasteRepository = pasteRepository;
        this.shortKeyGenerator = shortKeyGenerator;
        this.contentRouter = contentRouter;
        this.contentStorage = contentStorage;
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

    private Optional<CreatePasteResult> checkIdempotency(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        Optional<CreatePasteResult> cached = idempotencyStore.get(key, CreatePasteResult.class);
        if (cached.isPresent()) return cached.map(CreatePasteResult::asIdempotentReplay);
        if (!idempotencyStore.tryAcquire(key)) {
            return Optional.of(idempotencyStore.get(key, CreatePasteResult.class)
                    .orElseThrow(() -> new DomainException("Duplicate paste creation in progress")));
        }
        return Optional.empty();
    }

    private CreatePasteResult doCreatePaste(CreatePasteCommand command, Optional<UserId> ownerId) {
        Optional<CreatePasteResult> idempotent = checkIdempotency(command.idempotencyKey());
        if (idempotent.isPresent()) {
            return idempotent.get();
        }

        Instant now = Instant.now();
        PasteId pasteId = PasteId.generate();
        ShortKey shortKey = shortKeyGenerator.nextKey();
        String contentHash = ContentHasher.sha256(command.content());
        byte[] contentBytes = command.content().getBytes(StandardCharsets.UTF_8);

        String s3Key = contentStorage.buildKey(pasteId.toString());
        ContentRoutingDecision routing = contentRouter.route(command.content(), s3Key);

        if (routing.contentType() == com.pastebin.shared.ContentType.S3) {
            try {
                contentStorage.upload(s3Key, command.content());
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
        pasteRepository.scheduleExpiry(paste);

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
            return cached.get();
        }
        meterRegistry.counter("paste.read.cache_miss").increment();

        Paste paste = pasteRepository.findByShortKey(new ShortKey(key))
                .orElseThrow(() -> {
                    pasteCache.putNegative(key);
                    return new PasteNotFoundException("No paste found with key '" + key + "'");
                });

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
        return readTimer.record(() -> doGetPaste(key, requesterId, password)).content();
    }

    @Transactional
    public void deletePaste(String key, UserId ownerId) {
        Paste paste = pasteRepository.getByShortKey(new ShortKey(key));
        if (paste.isDeleted()) {
            return;
        }
        if (paste.getOwnerId() == null || !paste.getOwnerId().equals(ownerId)) {
            throw new com.pastebin.paste.domain.PasteNotAccessibleException("Only the paste owner can delete this paste");
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
        int pageSize = Math.clamp(limit, 1, 100);
        Instant cursorCreatedAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = decodeCursor(cursor);
            cursorCreatedAt = decoded.createdAt();
            cursorId = decoded.id();
        }

        List<Paste> pastes = pasteRepository.findByOwner(
                ownerId, Instant.now(), includeExpired,
                cursorCreatedAt, cursorId, pageSize + 1
        );

        boolean hasMore = pastes.size() > pageSize;
        List<Paste> page = hasMore ? pastes.subList(0, pageSize) : pastes;
        List<PasteSummary> items = page.stream().map(this::toSummary).toList();
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId().value())
                : null;
        return new PasteListResult(items, nextCursor, hasMore);
    }

    @Transactional
    public void markExpired(PasteId pasteId, DeletionReason reason) {
        Paste paste = pasteRepository.findById(pasteId)
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

    private String resolveContent(Paste paste) {
        if (paste.getContentType() == com.pastebin.shared.ContentType.INLINE) {
            return paste.getContentInline();
        }
        return contentStorage.download(paste.getContentS3Key());
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

    private PasteSummary toSummary(Paste paste) {
        return new PasteSummary(
                paste.getShortKey().value(),
                paste.getTitle(),
                paste.getLanguage(),
                paste.getAccessLevel(),
                paste.getViewCount(),
                paste.getContentSize(),
                paste.getExpiresAt(),
                paste.getCreatedAt()
        );
    }

    private ExpiryPolicy inferExpiryPolicy(Paste paste) {
        if (paste.getExpiresAt() == null) return ExpiryPolicy.NEVER;
        Duration elapsed = Duration.between(paste.getCreatedAt(), paste.getExpiresAt());
        for (ExpiryPolicy policy : ExpiryPolicy.values()) {
            if (policy != ExpiryPolicy.NEVER && elapsed.compareTo(policy.duration()) <= 0) {
                return policy;
            }
        }
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
