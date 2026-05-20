package com.test.banking.core.ledger.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.banking.core.account.api.AccountPublicApi;
import com.test.banking.core.account.api.dto.AccountBalanceDto;
import com.test.banking.core.shared.security.AccountAccessValidator;
import com.test.banking.core.ledger.api.dto.DepositRequest;
import com.test.banking.core.ledger.api.dto.DepositResponse;
import com.test.banking.core.ledger.api.dto.TransactionHistoryResponse;
import com.test.banking.core.ledger.api.dto.TransactionLineResponse;
import com.test.banking.core.ledger.api.dto.TransferRequest;
import com.test.banking.core.ledger.api.dto.TransferResponse;
import com.test.banking.core.ledger.infrastructure.JournalEntryEntity;
import com.test.banking.core.ledger.infrastructure.JournalEntryRepository;
import com.test.banking.core.shared.exception.BusinessRuleException;
import com.test.banking.core.shared.money.Money;
import com.test.banking.core.shared.util.RequestFingerprint;
import com.test.banking.core.shared.validation.IfscValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class TransactionService {

    private static final int HISTORY_MAX_ENTRIES = 500;

    private final PostingService postingService;
    private final IdempotencyService idempotencyService;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountPublicApi accountPublicApi;
    private final AccountAccessValidator accountAccessValidator;
    private final ObjectMapper objectMapper;

    public TransactionService(PostingService postingService, IdempotencyService idempotencyService,
                              JournalEntryRepository journalEntryRepository, AccountPublicApi accountPublicApi,
                              AccountAccessValidator accountAccessValidator, ObjectMapper objectMapper) {
        this.postingService = postingService;
        this.idempotencyService = idempotencyService;
        this.journalEntryRepository = journalEntryRepository;
        this.accountPublicApi = accountPublicApi;
        this.accountAccessValidator = accountAccessValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DepositResponse deposit(DepositRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        accountAccessValidator.assertCanAccessAccount(request.accountId());
        validateRemitterIfsc(request.remitterIfsc());
        String fingerprint = requestFingerprint(request);
        idempotencyService.assertPayloadMatches(idempotencyKey, fingerprint);
        return idempotencyService.execute(idempotencyKey, fingerprint, DepositResponse.class,
                () -> executeDeposit(request, idempotencyKey));
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        accountAccessValidator.assertCanAccessAccount(request.fromAccountId());
        accountAccessValidator.assertCanAccessAccount(request.toAccountId());
        String fingerprint = requestFingerprint(request);
        idempotencyService.assertPayloadMatches(idempotencyKey, fingerprint);
        return idempotencyService.execute(idempotencyKey, fingerprint, TransferResponse.class,
                () -> executeTransfer(request, idempotencyKey));
    }

    @Transactional(readOnly = true)
    public TransactionHistoryResponse getHistory(String accountId, LocalDate fromDate, LocalDate toDate,
                                                 int page, int size) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        accountPublicApi.assertAccountActive(accountId);
        LocalDate to = toDate != null ? toDate : LocalDate.now();
        LocalDate from = fromDate != null ? fromDate : to.minusDays(90);
        validateDateRange(from, to);

        int pageSize = Math.min(size, 100);
        List<JournalEntryEntity> fetched = journalEntryRepository.findAccountHistoryAsc(
                accountId, from, to, PageRequest.of(0, HISTORY_MAX_ENTRIES + 1));
        boolean truncated = fetched.size() > HISTORY_MAX_ENTRIES;
        List<JournalEntryEntity> ascEntries = truncated
                ? fetched.subList(0, HISTORY_MAX_ENTRIES)
                : fetched;

        AccountBalanceDto balance = accountPublicApi.getBalance(accountId);
        long netInRange = journalEntryRepository.netChangePaiseInRange(accountId, from, to);
        long balanceAtEndOfTo = balance.currentBalancePaise();
        if (to.isBefore(LocalDate.now())) {
            long netAfterTo = journalEntryRepository.netChangePaiseAfter(accountId, to);
            balanceAtEndOfTo -= netAfterTo;
        }
        long runningPaise = balanceAtEndOfTo - netInRange;

        List<TransactionLineResponse> computed = new ArrayList<>();
        for (JournalEntryEntity entry : ascEntries) {
            if ("C".equals(entry.getEntryType())) {
                runningPaise += entry.getAmountPaise();
            } else {
                runningPaise -= entry.getAmountPaise();
            }
            computed.add(toLine(entry, runningPaise));
        }
        Collections.reverse(computed);

        int fromIndex = page * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, computed.size());
        List<TransactionLineResponse> pageLines = fromIndex < computed.size()
                ? computed.subList(fromIndex, toIndex)
                : List.of();

        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) computed.size() / pageSize);
        return new TransactionHistoryResponse(accountId, pageLines,
                new TransactionHistoryResponse.Pagination(page, pageSize,
                        computed.size(), totalPages, toIndex < computed.size(), truncated));
    }

    private TransactionLineResponse toLine(JournalEntryEntity entry, long runningBalancePaise) {
        return new TransactionLineResponse(
                entry.getTxnId(),
                "D".equals(entry.getEntryType()) ? "DEBIT" : "CREDIT",
                Money.ofPaise(entry.getAmountPaise()).toRupees(),
                "INR",
                entry.getNarration(),
                entry.getPostingDate(),
                entry.getValueDate(),
                Money.ofPaise(runningBalancePaise).toRupees());
    }

    private DepositResponse executeDeposit(DepositRequest request, String idempotencyKey) {
        long amountPaise = Money.ofRupees(request.amount()).paise();
        LocalDate valueDate = request.valueDate() != null ? request.valueDate() : LocalDate.now();

        String fingerprint = requestFingerprint(request);
        var posted = postingService.postDeposit(request.accountId(), amountPaise, valueDate,
                request.narration(), request.referenceNumber(), idempotencyKey, fingerprint, null);

        AccountBalanceDto after = accountPublicApi.lockAndGetBalance(request.accountId());
        DepositResponse response = new DepositResponse(posted.txnId(), "POSTED", request.accountId(),
                Money.ofPaise(after.currentBalancePaise()).toRupees(), valueDate, valueDate);
        postingService.updateResponseSnapshot(posted.txnId(), response);
        return response;
    }

    private TransferResponse executeTransfer(TransferRequest request, String idempotencyKey) {
        long amountPaise = Money.ofRupees(request.amount()).paise();
        LocalDate valueDate = request.valueDate() != null ? request.valueDate() : LocalDate.now();

        String fingerprint = requestFingerprint(request);
        var posted = postingService.postTransfer(request.fromAccountId(), request.toAccountId(), amountPaise,
                valueDate, request.narration(), idempotencyKey, fingerprint, null);

        AccountBalanceDto fromAfter = accountPublicApi.lockAndGetBalance(request.fromAccountId());
        AccountBalanceDto toAfter = accountPublicApi.lockAndGetBalance(request.toAccountId());

        TransferResponse response = new TransferResponse(posted.txnId(), "POSTED",
                Money.ofPaise(fromAfter.currentBalancePaise()).toRupees(),
                Money.ofPaise(toAfter.currentBalancePaise()).toRupees(),
                valueDate, valueDate);
        postingService.updateResponseSnapshot(posted.txnId(), response);
        return response;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessRuleException("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required");
        }
    }

    private void validateRemitterIfsc(String remitterIfsc) {
        if (remitterIfsc != null && !remitterIfsc.isBlank()) {
            IfscValidator.normalizeAndValidate(remitterIfsc);
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessRuleException("INVALID_DATE_RANGE", "fromDate must not be after toDate");
        }
    }

    private String requestFingerprint(Object request) {
        return RequestFingerprint.of(objectMapper, request);
    }
}
