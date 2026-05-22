package com.fintech.ledger.repository;

import com.fintech.ledger.domain.Account;
import com.fintech.ledger.domain.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountCode(String accountCode);

    List<Account> findByOwnerIdAndStatus(UUID ownerId, AccountStatus status);

    @Query("SELECT a FROM Account a WHERE a.accountId IN :ids")
    List<Account> findAllByIds(@Param("ids") List<UUID> ids);

    boolean existsByAccountCode(String accountCode);
}
