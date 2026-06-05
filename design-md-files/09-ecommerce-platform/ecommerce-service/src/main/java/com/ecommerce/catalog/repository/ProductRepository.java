package com.ecommerce.catalog.repository;

import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryIdAndStatus(UUID categoryId, ProductStatus status, Pageable pageable);

    /**
     * Postgres full-text search over the DB-maintained search_vector column (MVP search).
     * plainto_tsquery handles arbitrary user input safely (no injection, no operator parsing).
     */
    @Query(
            value = """
                    SELECT * FROM products
                    WHERE status = 'ACTIVE'
                      AND search_vector @@ plainto_tsquery('english', :q)
                    ORDER BY ts_rank(search_vector, plainto_tsquery('english', :q)) DESC
                    """,
            countQuery = """
                    SELECT count(*) FROM products
                    WHERE status = 'ACTIVE'
                      AND search_vector @@ plainto_tsquery('english', :q)
                    """,
            nativeQuery = true)
    Page<Product> search(@Param("q") String query, Pageable pageable);
}
