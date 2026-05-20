package com.test.banking.core.account.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LienRepository extends JpaRepository<LienEntity, UUID> {

    @Query("""
            SELECT COALESCE(SUM(l.amountPaise), 0) FROM LienEntity l
            WHERE l.accountId = :accountId AND l.status = 'ACTIVE'
            """)
    long sumActiveLiensPaise(@Param("accountId") String accountId);

    List<LienEntity> findByAccountIdAndStatus(String accountId, String status);
}
