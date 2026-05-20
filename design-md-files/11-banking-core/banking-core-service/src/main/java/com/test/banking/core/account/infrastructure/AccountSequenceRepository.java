package com.test.banking.core.account.infrastructure;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class AccountSequenceRepository {

    private final EntityManager entityManager;

    public AccountSequenceRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public long nextAccountSequence() {
        String sql = isH2()
                ? "SELECT NEXT VALUE FOR accounts.account_number_seq"
                : "SELECT nextval('accounts.account_number_seq')";
        Number value = (Number) entityManager.createNativeQuery(sql).getSingleResult();
        return value.longValue();
    }

    private boolean isH2() {
        String dialect = entityManager.getEntityManagerFactory()
                .getProperties()
                .getOrDefault("hibernate.dialect", "")
                .toString();
        return dialect.toLowerCase().contains("h2");
    }
}
