package com.test.banking.core.account.application;

import com.test.banking.core.account.api.dto.AccountResponse;
import com.test.banking.core.account.api.dto.BalanceResponse;
import com.test.banking.core.account.api.dto.OpenAccountRequest;
import com.test.banking.core.account.infrastructure.AccountEntity;
import com.test.banking.core.account.infrastructure.AccountRepository;
import com.test.banking.core.account.infrastructure.AccountSequenceRepository;
import com.test.banking.core.account.infrastructure.OpenIdempotencyClaimEntity;
import com.test.banking.core.account.infrastructure.OpenIdempotencyClaimRepository;
import com.test.banking.core.account.infrastructure.OpenIdempotencyEntity;
import com.test.banking.core.account.infrastructure.LienRepository;
import com.test.banking.core.account.infrastructure.OpenIdempotencyRepository;
import com.test.banking.core.shared.util.RequestFingerprint;
import com.test.banking.core.shared.security.AccountAccessValidator;
import com.test.banking.core.kyc.api.KycPublicApi;
import com.test.banking.core.account.application.event.AccountOpenedEvent;
import com.test.banking.core.shared.audit.AuditService;
import com.test.banking.core.shared.exception.BusinessRuleException;
import com.test.banking.core.shared.exception.ConflictException;
import com.test.banking.core.shared.exception.NotFoundException;
import com.test.banking.core.shared.money.Money;
import com.test.banking.core.shared.util.AccountNumberGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountSequenceRepository sequenceRepository;
    private final OpenIdempotencyRepository openIdempotencyRepository;
    private final OpenIdempotencyClaimRepository openIdempotencyClaimRepository;
    private final KycPublicApi kycPublicApi;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final AccountAccessValidator accountAccessValidator;
    private final AvailableBalanceCalculator availableBalanceCalculator;
    private final LienRepository lienRepository;
    private final ObjectMapper objectMapper;

    public AccountService(AccountRepository accountRepository, AccountSequenceRepository sequenceRepository,
                          OpenIdempotencyRepository openIdempotencyRepository,
                          OpenIdempotencyClaimRepository openIdempotencyClaimRepository,
                          KycPublicApi kycPublicApi,
                          ApplicationEventPublisher eventPublisher, AuditService auditService,
                          AccountAccessValidator accountAccessValidator,
                          AvailableBalanceCalculator availableBalanceCalculator,
                          LienRepository lienRepository, ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.sequenceRepository = sequenceRepository;
        this.openIdempotencyRepository = openIdempotencyRepository;
        this.openIdempotencyClaimRepository = openIdempotencyClaimRepository;
        this.kycPublicApi = kycPublicApi;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.accountAccessValidator = accountAccessValidator;
        this.availableBalanceCalculator = availableBalanceCalculator;
        this.lienRepository = lienRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AccountResponse openAccount(OpenAccountRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        accountAccessValidator.assertCanAccessCustomer(request.cifId());

        String fingerprint = RequestFingerprint.of(objectMapper, request);
        var existing = openIdempotencyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            assertOpenPayloadMatches(existing.get(), fingerprint);
            return getAccount(existing.get().getAccountId());
        }

        try {
            OpenIdempotencyClaimEntity claim = new OpenIdempotencyClaimEntity();
            claim.setIdempotencyKey(idempotencyKey);
            claim.setCreatedAt(Instant.now());
            openIdempotencyClaimRepository.save(claim);
        } catch (DataIntegrityViolationException ex) {
            return resolveOpenIdempotencyConflict(idempotencyKey, fingerprint);
        }

        if (!kycPublicApi.customerExists(request.cifId())) {
            throw new NotFoundException("Customer not found: " + request.cifId());
        }
        if (!kycPublicApi.isKycVerified(request.cifId())) {
            throw new BusinessRuleException("KYC_EXPIRED", "Customer KYC is not verified");
        }
        if (!"SAVINGS".equalsIgnoreCase(request.accountType())) {
            throw new BusinessRuleException("UNSUPPORTED_ACCOUNT_TYPE", "Only SAVINGS supported in MVP");
        }

        long seq = sequenceRepository.nextAccountSequence();
        String accountId = AccountNumberGenerator.savingsAccountId(seq);
        Instant now = Instant.now();
        long initialPaise = request.initialDeposit() != null
                ? Money.ofRupees(request.initialDeposit()).paise()
                : 0L;

        AccountEntity account = new AccountEntity();
        account.setAccountId(accountId);
        account.setCifId(request.cifId());
        account.setAccountType("SAVINGS");
        account.setProductCode(request.productCode() != null ? request.productCode() : "SAV_BASIC");
        account.setStatus("ACTIVE");
        account.setCurrency("INR");
        account.setCurrentBalancePaise(0L);
        account.setAvailableBalancePaise(0L);
        account.setOverdraftLimitPaise(0L);
        account.setOpenDate(LocalDate.now());
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountRepository.save(account);

        if (initialPaise > 0) {
            eventPublisher.publishEvent(new AccountOpenedEvent(
                    accountId, initialPaise, idempotencyKey + "-opening-deposit", LocalDate.now()));
        }

        try {
            OpenIdempotencyEntity openRecord = new OpenIdempotencyEntity();
            openRecord.setIdempotencyKey(idempotencyKey);
            openRecord.setAccountId(accountId);
            openRecord.setCreatedAt(Instant.now());
            openRecord.setRequestFingerprint(fingerprint);
            openIdempotencyRepository.save(openRecord);
        } catch (DataIntegrityViolationException ex) {
            return resolveOpenIdempotencyConflict(idempotencyKey, fingerprint);
        }

        AccountResponse response = getAccount(accountId);
        auditService.record("ACCOUNT_OPENED", "ACCOUNT", accountId, response);
        return response;
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        if (!AccountNumberGenerator.isValidSavingsAccountId(accountId)) {
            throw new BusinessRuleException("INVALID_ACCOUNT_ID", "Invalid account number check digit");
        }
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        availableBalanceCalculator.refreshAvailableBalance(account);
        String kycStatus = kycPublicApi.isKycVerified(account.getCifId()) ? "VERIFIED" : "PENDING";
        return toResponse(account, kycStatus);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        accountAccessValidator.assertCanAccessAccount(accountId);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        availableBalanceCalculator.refreshAvailableBalance(account);
        return new BalanceResponse(
                account.getAccountId(),
                Money.ofPaise(account.getCurrentBalancePaise()).toRupees(),
                Money.ofPaise(account.getAvailableBalancePaise()).toRupees(),
                account.getCurrency(),
                Instant.now()
        );
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessRuleException("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required");
        }
    }

    private void assertOpenPayloadMatches(OpenIdempotencyEntity existing, String fingerprint) {
        if (existing.getRequestFingerprint() == null) {
            return;
        }
        if (fingerprint == null || !existing.getRequestFingerprint().equals(fingerprint)) {
            throw new ConflictException("DUPLICATE_TRANSACTION",
                    "Idempotency key already used with a different request payload");
        }
    }

    private AccountResponse resolveOpenIdempotencyConflict(String idempotencyKey, String fingerprint) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var record = openIdempotencyRepository.findById(idempotencyKey);
            if (record.isPresent()) {
                assertOpenPayloadMatches(record.get(), fingerprint);
                return getAccount(record.get().getAccountId());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ConflictException("IN_PROGRESS",
                "Account open with this idempotency key is still being processed");
    }

    private AccountResponse toResponse(AccountEntity account, String kycStatus) {
        long liensPaise = lienRepository.sumActiveLiensPaise(account.getAccountId());
        return new AccountResponse(
                account.getAccountId(),
                account.getCifId(),
                account.getAccountType(),
                account.getStatus(),
                account.getCurrency(),
                Money.ofPaise(account.getCurrentBalancePaise()).toRupees(),
                Money.ofPaise(account.getAvailableBalancePaise()).toRupees(),
                Money.ofPaise(liensPaise).toRupees(),
                account.getProductCode(),
                account.getOpenDate(),
                kycStatus
        );
    }
}
