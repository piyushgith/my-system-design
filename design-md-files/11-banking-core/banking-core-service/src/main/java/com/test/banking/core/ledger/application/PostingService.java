package com.test.banking.core.ledger.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.banking.core.account.api.AccountPublicApi;
import com.test.banking.core.account.api.dto.AccountBalanceDto;
import com.test.banking.core.ledger.infrastructure.JournalEntryEntity;
import com.test.banking.core.ledger.infrastructure.JournalEntryRepository;
import com.test.banking.core.ledger.infrastructure.TransactionEntity;
import com.test.banking.core.ledger.infrastructure.TransactionRepository;
import com.test.banking.core.shared.audit.AuditService;
import com.test.banking.core.shared.exception.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.test.banking.core.shared.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountPublicApi accountPublicApi;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final String clearingGlCode;

    public PostingService(TransactionRepository transactionRepository,
                          JournalEntryRepository journalEntryRepository,
                          AccountPublicApi accountPublicApi,
                          AuditService auditService,
                          ObjectMapper objectMapper,
                          @Value("${banking.ledger.clearing-gl-code}") String clearingGlCode) {
        this.transactionRepository = transactionRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.accountPublicApi = accountPublicApi;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clearingGlCode = clearingGlCode;
    }

    @Transactional
    public PostedTransaction postDeposit(String accountId, long amountPaise, LocalDate valueDate,
                                         String narration, String referenceNumber, String idempotencyKey,
                                         String requestFingerprint, Object responsePayload) {
        accountPublicApi.assertAccountActive(accountId);

        String txnId = newTxnId();
        TransactionEntity txn = createTransaction(txnId, "DEPOSIT", amountPaise, valueDate, narration,
                referenceNumber, idempotencyKey, requestFingerprint);

        List<JournalEntryEntity> entries = new ArrayList<>();
        entries.add(journalEntry(txnId, accountId, null, 'C', amountPaise, valueDate, narration));
        entries.add(journalEntry(txnId, null, clearingGlCode, 'D', amountPaise, valueDate, narration));
        journalEntryRepository.saveAll(entries);
        assertDoubleEntryBalanced(txnId, entries);

        // creditAccount holds the pessimistic lock and returns the post-credit balance,
        // avoiding a redundant SELECT ... FOR UPDATE in the caller.
        AccountBalanceDto balanceAfter = accountPublicApi.creditAccount(accountId, amountPaise);
        finalizeTransaction(txn, responsePayload);
        auditService.record("DEPOSIT_POSTED", "TRANSACTION", txnId, responsePayload);
        log.info("Deposit posted txnId={} accountId={} amountPaise={}", txnId, accountId, amountPaise);
        return new PostedTransaction(txnId, valueDate, txn, balanceAfter, null);
    }

    @Transactional
    public PostedTransaction postTransfer(String fromAccountId, String toAccountId, long amountPaise,
                                          LocalDate valueDate, String narration, String idempotencyKey,
                                          String requestFingerprint, Object responsePayload) {
        if (fromAccountId.equals(toAccountId)) {
            throw new BusinessRuleException("INVALID_TRANSFER", "Cannot transfer to the same account");
        }

        String first = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
        String second = first.equals(fromAccountId) ? toAccountId : fromAccountId;
        accountPublicApi.lockAndGetBalance(first);
        accountPublicApi.lockAndGetBalance(second);

        String txnId = newTxnId();
        TransactionEntity txn = createTransaction(txnId, "TRANSFER", amountPaise, valueDate, narration,
                null, idempotencyKey, requestFingerprint);

        List<JournalEntryEntity> entries = new ArrayList<>();
        entries.add(journalEntry(txnId, fromAccountId, "GL-TRANSFER", 'D', amountPaise, valueDate, narration));
        entries.add(journalEntry(txnId, toAccountId, "GL-TRANSFER", 'C', amountPaise, valueDate, narration));
        journalEntryRepository.saveAll(entries);
        assertDoubleEntryBalanced(txnId, entries);

        // debit/credit hold the per-account lock and return post-mutation balances,
        // avoiding redundant SELECT ... FOR UPDATE reads in the caller.
        AccountBalanceDto fromAfter = accountPublicApi.debitAccount(fromAccountId, amountPaise);
        AccountBalanceDto toAfter = accountPublicApi.creditAccount(toAccountId, amountPaise);
        finalizeTransaction(txn, responsePayload);
        auditService.record("TRANSFER_POSTED", "TRANSACTION", txnId, responsePayload);
        log.info("Transfer posted txnId={} from={} to={} amountPaise={}",
                txnId, fromAccountId, toAccountId, amountPaise);
        return new PostedTransaction(txnId, valueDate, txn, fromAfter, toAfter);
    }

    private TransactionEntity createTransaction(String txnId, String txnType, long amountPaise,
                                                LocalDate valueDate, String narration, String referenceNumber,
                                                String idempotencyKey, String requestFingerprint) {
        TransactionEntity txn = new TransactionEntity();
        txn.setTxnId(txnId);
        txn.setTxnType(txnType);
        txn.setStatus("PENDING");
        txn.setAmountPaise(amountPaise);
        txn.setCurrency("INR");
        txn.setPostingDate(LocalDate.now());
        txn.setValueDate(valueDate);
        txn.setNarration(narration);
        txn.setReferenceNumber(referenceNumber);
        txn.setIdempotencyKey(idempotencyKey);
        txn.setRequestFingerprint(requestFingerprint);
        txn.setInitiatedBy(currentUser());
        txn.setInitiatedAt(Instant.now());
        return transactionRepository.save(txn);
    }

    private void finalizeTransaction(TransactionEntity txn, Object responsePayload) {
        txn.setStatus("POSTED");
        txn.setPostedAt(Instant.now());
        if (responsePayload != null) {
            try {
                txn.setResponseSnapshot(objectMapper.writeValueAsString(responsePayload));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize response snapshot for txn {}: {}",
                        txn.getTxnId(), e.getMessage());
                txn.setResponseSnapshot("{}");
            }
        }
        transactionRepository.save(txn);
    }

    public void updateResponseSnapshot(String txnId, Object responsePayload) {
        transactionRepository.findById(txnId).ifPresent(txn -> finalizeTransaction(txn, responsePayload));
    }

    private JournalEntryEntity journalEntry(String txnId, String accountId, String glCode, char entryType,
                                            long amountPaise, LocalDate valueDate, String narration) {
        JournalEntryEntity entry = new JournalEntryEntity();
        entry.setEntryId(UUID.randomUUID());
        entry.setTxnId(txnId);
        entry.setAccountId(accountId);
        entry.setGlCode(glCode != null ? glCode : "GL-INTERNAL");
        entry.setEntryType(String.valueOf(entryType));
        entry.setAmountPaise(amountPaise);
        entry.setCurrency("INR");
        entry.setValueDate(valueDate);
        entry.setPostingDate(LocalDate.now());
        entry.setPostedAt(Instant.now());
        entry.setNarration(narration);
        return entry;
    }

    private String newTxnId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return "TXN-" + date + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String currentUser() {
        return SecurityUtils.currentUserId();
    }

    private void assertDoubleEntryBalanced(String txnId, List<JournalEntryEntity> entries) {
        long debits = 0L;
        long credits = 0L;
        for (JournalEntryEntity entry : entries) {
            if ("D".equals(entry.getEntryType())) {
                debits += entry.getAmountPaise();
            } else {
                credits += entry.getAmountPaise();
            }
        }
        if (debits != credits) {
            log.error("Double-entry imbalance txnId={} debits={} credits={}", txnId, debits, credits);
            throw new BusinessRuleException("LEDGER_IMBALANCE",
                    "Double-entry imbalance for transaction " + txnId);
        }
    }

    public record PostedTransaction(String txnId, LocalDate valueDate, TransactionEntity transaction,
                                    AccountBalanceDto primaryBalance, AccountBalanceDto secondaryBalance) {
    }
}
