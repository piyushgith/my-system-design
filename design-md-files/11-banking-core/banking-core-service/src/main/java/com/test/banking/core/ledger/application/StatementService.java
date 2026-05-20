package com.test.banking.core.ledger.application;

import com.test.banking.core.account.api.AccountPublicApi;
import com.test.banking.core.shared.security.AccountAccessValidator;
import com.test.banking.core.ledger.api.dto.StatementLineResponse;
import com.test.banking.core.ledger.api.dto.StatementRequest;
import com.test.banking.core.ledger.api.dto.StatementResponse;
import com.test.banking.core.ledger.infrastructure.JournalEntryEntity;
import com.test.banking.core.ledger.infrastructure.JournalEntryRepository;
import com.test.banking.core.shared.exception.BusinessRuleException;
import com.test.banking.core.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class StatementService {

    private final JournalEntryRepository journalEntryRepository;
    private final AccountPublicApi accountPublicApi;
    private final AccountAccessValidator accountAccessValidator;

    public StatementService(JournalEntryRepository journalEntryRepository, AccountPublicApi accountPublicApi,
                            AccountAccessValidator accountAccessValidator) {
        this.journalEntryRepository = journalEntryRepository;
        this.accountPublicApi = accountPublicApi;
        this.accountAccessValidator = accountAccessValidator;
    }

    @Transactional(readOnly = true)
    public StatementResponse generateStatement(String accountId, StatementRequest request) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        accountPublicApi.assertAccountActive(accountId);
        LocalDate to = request.toDate();
        LocalDate from = request.fromDate();
        if (from.isAfter(to)) {
            throw new BusinessRuleException("INVALID_DATE_RANGE", "fromDate must not be after toDate");
        }

        List<JournalEntryEntity> entries = journalEntryRepository
                .findAccountHistory(accountId, from, to,
                        org.springframework.data.domain.PageRequest.of(0, 1000))
                .getContent();

        List<StatementLineResponse> lines = entries.stream()
                .map(e -> new StatementLineResponse(
                        e.getPostingDate(),
                        e.getTxnId(),
                        e.getEntryType(),
                        Money.ofPaise(e.getAmountPaise()).toRupees(),
                        e.getNarration()))
                .toList();

        return new StatementResponse(accountId, from, to, request.format(), lines);
    }
}
