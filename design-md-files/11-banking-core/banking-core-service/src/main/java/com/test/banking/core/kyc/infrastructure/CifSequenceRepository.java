package com.test.banking.core.kyc.infrastructure;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class CifSequenceRepository {

    private final EntityManager entityManager;

    public CifSequenceRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public long nextCifSequence() {
        String sql = isH2()
                ? "SELECT NEXT VALUE FOR cif.cif_number_seq"
                : "SELECT nextval('cif.cif_number_seq')";
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
