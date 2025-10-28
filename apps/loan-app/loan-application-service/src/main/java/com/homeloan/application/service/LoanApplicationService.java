package com.homeloan.application.service;


import com.homeloan.application.dto.ApplicationStatus;
import com.homeloan.application.entity.LoanApplication;
import com.homeloan.application.mapper.LoanApplicationMapper;
import com.homeloan.application.repository.LoanApplicationRepository;
import com.homeloan.creditcheck.dto.LoanApplicationDto;
import com.homeloan.creditcheck.events.LoanApplicationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class LoanApplicationService {

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired//(required=false)
    private LoanApplicationMapper mapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public LoanApplicationDto createLoanApplication(LoanApplicationDto dto) {
        log.info("Processing loan application with email id: {}", dto.getApplicantEmail());
        long activeApplications = loanApplicationRepository.countByApplicantEmailAndApplicationStatusIn(dto.getApplicantEmail());

        if (activeApplications > 3) {
            log.warn("Applicant with email {} has {} active applications. Rejecting new application.", dto.getApplicantEmail(), activeApplications);
            // Additional logic to handle rejection can be added here
            //return;
        }

        LoanApplication entity = mapper.toEntity(dto,UUID.randomUUID().toString());
        entity = loanApplicationRepository.save(entity);
        log.info("Loan application saved with id: {}", entity.getId());

        //publish event to Kafka
        publishLoanApplicationEvent(entity, LoanApplicationEvent.EventType.LOAN_APPLICATION_CREATED);

        log.info("Published loan application created event for application id: {} Email: {}", entity.getId(), entity.getApplicantEmail());
        return mapper.toDto(entity);
    }

    public Optional<LoanApplicationDto> getLoanApplicationById(Long id) {
        log.info("Fetching loan application with id: {}", id);
        return loanApplicationRepository.findById(id)
                .map(mapper::toDto);
    }

    public List<LoanApplicationDto> getLoanApplicationByEmail(String email) {
        log.info("Fetching loan applications for applicant email: {}", email);
        return loanApplicationRepository.findByApplicantEmail(email)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public LoanApplicationDto updateApplicationStatus(Long applicationId, ApplicationStatus newStatus) {
        log.info("Updating application id: {} to status: {}", applicationId, newStatus);
        Optional<LoanApplication> application = loanApplicationRepository.findById(applicationId);
        if (application.isEmpty()) {
            log.error("Loan application with id: {} not found", applicationId);
            throw new RuntimeException("Loan application not found");
        }
        LoanApplication applicationEntity = application.get();
        applicationEntity.setApplicationStatus(newStatus);

        LoanApplication updated_application = loanApplicationRepository.save(applicationEntity);
        log.info("Application id: {} status updated to: {}", applicationId, newStatus);

        // Publish event to Kafka
        publishLoanApplicationEvent(updated_application, LoanApplicationEvent.EventType.LOAN_APPLICATION_UPDATED);
        log.info("Published loan application updated event for application id: {} Email: {}", updated_application.getId(), updated_application.getApplicantEmail());
        return mapper.toDto(updated_application);
    }


    //@formatter:off
    private void publishLoanApplicationEvent(LoanApplication application, LoanApplicationEvent.EventType eventType) {
        LoanApplicationEvent event = LoanApplicationEvent.builder()
                .applicationId(application.getId())
                .applicantName(application.getApplicantName())
                .applicantEmail(application.getApplicantEmail())
                .applicantPhone(application.getApplicantPhone())
                .loanAmount(application.getLoanAmount())
                .propertyAddress(application.getPropertyAddress())
                .applicationStatus(application.getApplicationStatus().toString())
                .eventTime(java.time.LocalDateTime.now())
                .eventType(eventType.toString())
                .sagaId(application.getSagaId())
                .build();

        kafkaTemplate.send("loan-application-events", event);
        log.info("Loan application event sent to Kafka for application id: {} Event Type: {}", application.getId(), eventType);
    }
    //@formatter:on
}
