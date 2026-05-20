package com.test.banking.core.ledger.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, UUID> {

    List<JournalEntryEntity> findByTxnId(String txnId);

    @Query("""
            SELECT j FROM JournalEntryEntity j
            WHERE j.accountId = :accountId
              AND j.postingDate >= :fromDate
              AND j.postingDate <= :toDate
            ORDER BY j.postingDate DESC, j.postedAt DESC
            """)
    Page<JournalEntryEntity> findAccountHistory(
            @Param("accountId") String accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable);

    @Query("""
            SELECT j FROM JournalEntryEntity j
            WHERE j.accountId = :accountId
              AND j.postingDate >= :fromDate
              AND j.postingDate <= :toDate
            ORDER BY j.postingDate ASC, j.postedAt ASC
            """)
    List<JournalEntryEntity> findAccountHistoryAsc(
            @Param("accountId") String accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(CASE WHEN j.entryType = 'C' THEN j.amountPaise ELSE -j.amountPaise END), 0)
            FROM JournalEntryEntity j
            WHERE j.accountId = :accountId
              AND j.postingDate >= :fromDate
              AND j.postingDate <= :toDate
            """)
    long netChangePaiseInRange(
            @Param("accountId") String accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    @Query("""
            SELECT COALESCE(SUM(CASE WHEN j.entryType = 'C' THEN j.amountPaise ELSE -j.amountPaise END), 0)
            FROM JournalEntryEntity j
            WHERE j.accountId = :accountId
              AND j.postingDate > :afterDate
            """)
    long netChangePaiseAfter(
            @Param("accountId") String accountId,
            @Param("afterDate") LocalDate afterDate);
}
