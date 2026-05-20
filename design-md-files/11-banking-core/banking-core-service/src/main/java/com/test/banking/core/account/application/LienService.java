package com.test.banking.core.account.application;

import com.test.banking.core.account.api.dto.LienResponse;
import com.test.banking.core.account.api.dto.PlaceLienRequest;
import com.test.banking.core.account.infrastructure.AccountEntity;
import com.test.banking.core.account.infrastructure.AccountRepository;
import com.test.banking.core.account.infrastructure.LienEntity;
import com.test.banking.core.account.infrastructure.LienRepository;
import com.test.banking.core.shared.audit.AuditService;
import com.test.banking.core.shared.exception.BusinessRuleException;
import com.test.banking.core.shared.exception.NotFoundException;
import com.test.banking.core.shared.money.Money;
import com.test.banking.core.shared.security.AccountAccessValidator;
import com.test.banking.core.shared.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LienService {

    private final AccountRepository accountRepository;
    private final LienRepository lienRepository;
    private final AvailableBalanceCalculator availableBalanceCalculator;
    private final AccountAccessValidator accountAccessValidator;
    private final AuditService auditService;

    public LienService(AccountRepository accountRepository, LienRepository lienRepository,
                       AvailableBalanceCalculator availableBalanceCalculator,
                       AccountAccessValidator accountAccessValidator, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.lienRepository = lienRepository;
        this.availableBalanceCalculator = availableBalanceCalculator;
        this.accountAccessValidator = accountAccessValidator;
        this.auditService = auditService;
    }

    @Transactional
    public LienResponse placeLien(String accountId, PlaceLienRequest request) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        AccountEntity account = loadActiveAccountForUpdate(accountId);
        availableBalanceCalculator.refreshAvailableBalance(account);

        long lienPaise = Money.ofRupees(request.amount()).paise();
        if (lienPaise > account.getAvailableBalancePaise()) {
            throw new BusinessRuleException("LIEN_EXCEEDS_BALANCE",
                    "Lien amount exceeds available balance");
        }

        LienEntity lien = new LienEntity();
        lien.setLienId(UUID.randomUUID());
        lien.setAccountId(accountId);
        lien.setAmountPaise(lienPaise);
        lien.setReason(request.reason());
        lien.setStatus("ACTIVE");
        lien.setLienType(request.lienType() != null ? request.lienType() : "PAYMENT_HOLD");
        lien.setReferenceId(request.referenceId());
        lien.setCreatedAt(Instant.now());
        lienRepository.save(lien);

        availableBalanceCalculator.refreshAvailableBalance(account);
        LienResponse response = toResponse(lien);
        auditService.record("LIEN_PLACED", "LIEN", lien.getLienId().toString(), response);
        return response;
    }

    @Transactional
    public LienResponse releaseLien(String accountId, UUID lienId) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        LienEntity lien = lienRepository.findById(lienId)
                .filter(l -> accountId.equals(l.getAccountId()))
                .orElseThrow(() -> new NotFoundException("Lien not found: " + lienId));
        if (!"ACTIVE".equals(lien.getStatus())) {
            throw new BusinessRuleException("LIEN_NOT_ACTIVE", "Lien is not active");
        }

        lien.setStatus("RELEASED");
        lien.setReleasedAt(Instant.now());
        lien.setReleasedBy(SecurityUtils.currentUserId());

        AccountEntity account = loadActiveAccountForUpdate(accountId);
        availableBalanceCalculator.refreshAvailableBalance(account);

        LienResponse response = toResponse(lien);
        auditService.record("LIEN_RELEASED", "LIEN", lienId.toString(), response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<LienResponse> listActiveLiens(String accountId) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        return lienRepository.findByAccountIdAndStatus(accountId, "ACTIVE").stream()
                .map(this::toResponse)
                .toList();
    }

    private AccountEntity loadActiveAccountForUpdate(String accountId) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BusinessRuleException("ACCOUNT_FROZEN", "Account is not active: " + accountId);
        }
        return account;
    }

    private LienResponse toResponse(LienEntity lien) {
        return new LienResponse(
                lien.getLienId(),
                lien.getAccountId(),
                Money.ofPaise(lien.getAmountPaise()).toRupees(),
                lien.getReason(),
                lien.getStatus(),
                lien.getLienType(),
                lien.getCreatedAt()
        );
    }
}
