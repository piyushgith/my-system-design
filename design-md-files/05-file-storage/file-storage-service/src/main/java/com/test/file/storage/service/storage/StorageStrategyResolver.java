package com.test.file.storage.service.storage;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects the active {@link StorageStrategy} at runtime. All registered strategies are injected and
 * indexed by {@link StorageStrategy#name()}; the one named by {@code app.storage.backend} wins.
 *
 * <p>This is the seam that makes storage pluggable: services call {@code resolver.active()} and never
 * know which backend they got. Adding a new backend = add a new {@link StorageStrategy} bean.
 */
@Component
public class StorageStrategyResolver {

    private final Map<String, StorageStrategy> strategies;
    private final StorageProperties properties;

    public StorageStrategyResolver(List<StorageStrategy> strategies, StorageProperties properties) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(StorageStrategy::name, Function.identity()));
        this.properties = properties;
    }

    /** The backend selected by configuration. */
    public StorageStrategy active() {
        StorageStrategy strategy = strategies.get(properties.getBackend());
        if (strategy == null) {
            throw new StorageException("No storage backend registered for app.storage.backend=" + properties.getBackend()
                    + ". Available: " + strategies.keySet());
        }
        return strategy;
    }

    /** Look up a specific backend by name (e.g. to read an object stored under a different backend). */
    public StorageStrategy byName(String name) {
        StorageStrategy strategy = strategies.get(name);
        if (strategy == null) {
            throw new StorageException("Unknown storage backend: " + name + ". Available: " + strategies.keySet());
        }
        return strategy;
    }
}
