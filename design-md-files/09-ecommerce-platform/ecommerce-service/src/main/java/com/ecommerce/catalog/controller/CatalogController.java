package com.ecommerce.catalog.controller;

import com.ecommerce.catalog.service.CatalogService;
import com.ecommerce.catalog.service.dto.CategoryResponse;
import com.ecommerce.catalog.service.dto.ProductDetail;
import com.ecommerce.catalog.service.dto.ProductSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> listCategories() {
        return ResponseEntity.ok(catalogService.listCategories());
    }

    @GetMapping("/products")
    public ResponseEntity<Page<ProductSummary>> listProducts(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProductSummary> result = (q != null && !q.isBlank())
                ? catalogService.search(q, page, size)
                : catalogService.listProducts(categoryId, page, size);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<ProductDetail> getProduct(@PathVariable UUID productId) {
        return ResponseEntity.ok(catalogService.getProduct(productId));
    }
}
