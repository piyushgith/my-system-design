package com.ecommerce.catalog.service;

import com.ecommerce.catalog.domain.Category;
import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductStatus;
import com.ecommerce.catalog.repository.CategoryRepository;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.catalog.service.dto.*;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    // ---------- Public browsing ----------

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<ProductSummary> listProducts(UUID categoryId, int page, int size) {
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Product> products = (categoryId == null)
                ? productRepository.findByStatus(ProductStatus.ACTIVE, pageable)
                : productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ACTIVE, pageable);
        return products.map(ProductSummary::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductSummary> search(String query, int page, int size) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        // native query owns ordering (ts_rank); pass an unsorted pageable
        return productRepository.search(query, pageable(page, size, Sort.unsorted()))
                .map(ProductSummary::from);
    }

    @Transactional(readOnly = true)
    public ProductDetail getProduct(UUID productId) {
        Product product = productRepository.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        return ProductDetail.from(product);
    }

    // ---------- Admin management ----------

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new ConflictException("Category slug already exists");
        }
        Category category = Category.builder()
                .name(request.name())
                .slug(request.slug())
                .build();
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public ProductDetail createProduct(CreateProductRequest request) {
        if (!categoryRepository.existsById(request.categoryId())) {
            throw new NotFoundException("Category not found");
        }
        Product product = Product.builder()
                .categoryId(request.categoryId())
                .title(request.title())
                .description(request.description())
                .priceAmount(request.priceAmount())
                .currency(request.currency() != null ? request.currency() : "INR")
                .stockQuantity(request.stockQuantity())
                .imageUrl(request.imageUrl())
                .status(ProductStatus.ACTIVE)
                .build();
        return ProductDetail.from(productRepository.save(product));
    }

    @Transactional
    public ProductDetail updateProduct(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        if (request.title() != null)         product.setTitle(request.title());
        if (request.description() != null)   product.setDescription(request.description());
        if (request.priceAmount() != null)   product.setPriceAmount(request.priceAmount());
        if (request.stockQuantity() != null) product.setStockQuantity(request.stockQuantity());
        if (request.imageUrl() != null)      product.setImageUrl(request.imageUrl());
        if (request.status() != null)        product.setStatus(parseStatus(request.status()));
        return ProductDetail.from(productRepository.save(product));
    }

    private static ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid product status: " + status);
        }
    }

    private static Pageable pageable(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }
}
