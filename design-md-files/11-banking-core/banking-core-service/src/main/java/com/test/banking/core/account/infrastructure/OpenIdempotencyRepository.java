package com.test.banking.core.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenIdempotencyRepository extends JpaRepository<OpenIdempotencyEntity, String> {
}
