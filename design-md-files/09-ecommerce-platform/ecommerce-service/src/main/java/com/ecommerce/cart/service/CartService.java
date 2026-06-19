package com.ecommerce.cart.service;

import com.ecommerce.cart.service.dto.AddCartItemRequest;
import com.ecommerce.cart.service.dto.CartItemResponse;
import com.ecommerce.cart.service.dto.CartResponse;
import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cart lives in Redis (ephemeral, fast). Key: cart:{userId}, a hash of
 * productId -> quantity. Product titles/prices are resolved from Postgres at
 * read time so the cart always reflects current catalog pricing.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    private static final String KEY_PREFIX = "cart:";

    private final StringRedisTemplate redis;
    private final ProductRepository productRepository;
    private final Duration ttl;

    public CartService(StringRedisTemplate redis,
                       ProductRepository productRepository,
                       @Value("${app.cart.ttl-hours:168}") long ttlHours) {
        this.redis = redis;
        this.productRepository = productRepository;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        Product product = productRepository.findById(request.productId())
                .filter(Product::isActive)
                .orElseThrow(() -> new NotFoundException("Product not found"));

        String key = key(userId);
        HashOperations<String, Object, Object> hash = redis.opsForHash();
        hash.increment(key, product.getId().toString(), request.quantity());
        redis.expire(key, ttl);
        return getCart(userId);
    }

    public CartResponse updateItem(UUID userId, UUID productId, int quantity) {
        String key = key(userId);
        HashOperations<String, Object, Object> hash = redis.opsForHash();
        if (quantity <= 0) {
            hash.delete(key, productId.toString());
        } else {
            // Reject writes for products that don't exist or are archived, so the cart
            // can't accumulate invalid lines that only get cleaned up later at read time.
            productRepository.findById(productId)
                    .filter(Product::isActive)
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            hash.put(key, productId.toString(), String.valueOf(quantity));
            redis.expire(key, ttl);
        }
        return getCart(userId);
    }

    public CartResponse getCart(UUID userId) {
        Map<UUID, Integer> raw = rawItems(userId);
        if (raw.isEmpty()) {
            return CartResponse.empty();
        }

        List<Product> products = productRepository.findAllById(raw.keySet());
        Map<UUID, Product> byId = new LinkedHashMap<>();
        products.forEach(p -> byId.put(p.getId(), p));

        List<CartItemResponse> items = new ArrayList<>();
        long subtotal = 0;
        String currency = "INR";
        for (Map.Entry<UUID, Integer> entry : raw.entrySet()) {
            Product product = byId.get(entry.getKey());
            if (product == null || !product.isActive()) {
                // product vanished or was archived — drop the stale line from Redis
                redis.opsForHash().delete(key(userId), entry.getKey().toString());
                continue;
            }
            int qty = entry.getValue();
            long lineTotal = product.getPriceAmount() * qty;
            subtotal += lineTotal;
            currency = product.getCurrency();
            items.add(new CartItemResponse(
                    product.getId(), product.getTitle(), product.getPriceAmount(),
                    qty, lineTotal, product.hasStock(qty)));
        }
        return new CartResponse(items, currency, subtotal);
    }

    public void clear(UUID userId) {
        redis.delete(key(userId));
    }

    /** Raw productId -> quantity map; used by checkout. */
    public Map<UUID, Integer> rawItems(UUID userId) {
        String key = key(userId);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        Map<UUID, Integer> result = new LinkedHashMap<>();
        entries.forEach((k, v) -> {
            try {
                result.put(UUID.fromString((String) k), Integer.parseInt((String) v));
            } catch (RuntimeException ex) {
                // Corrupt hash entry (bad UUID or non-numeric quantity) — drop it so a single
                // bad line can't break cart reads or checkout for the whole user.
                log.warn("Dropping corrupt cart entry for {}: field={}, value={}", key, k, v);
                redis.opsForHash().delete(key, k);
            }
        });
        return result;
    }

    private static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
