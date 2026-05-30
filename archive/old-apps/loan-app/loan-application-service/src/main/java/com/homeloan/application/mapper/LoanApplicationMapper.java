package com.homeloan.application.mapper;


import com.homeloan.application.entity.LoanApplication;
import com.homeloan.creditcheck.dto.LoanApplicationDto;
import org.springframework.stereotype.Component;

@Component
public class LoanApplicationMapper {

    public LoanApplicationDto toDto(LoanApplication loanApplication){
        if (loanApplication == null) {
            return null;
        }

        LoanApplicationDto dto = new LoanApplicationDto();
        dto.setId(loanApplication.getId());
        dto.setApplicantName(loanApplication.getApplicantName());
        dto.setApplicantEmail(loanApplication.getApplicantEmail());
        dto.setApplicantPhone(loanApplication.getApplicantPhone());
        dto.setPropertyAddress(loanApplication.getPropertyAddress());
        dto.setLoanAmount(loanApplication.getLoanAmount());
        dto.setApplicationStatus(loanApplication.getApplicationStatus());
        dto.setCreateAt(loanApplication.getCreatedAt());
        dto.setUpdatedAt(loanApplication.getUpdatedAt());
        return dto;
    }

    public LoanApplication toEntity(LoanApplicationDto loanApplicationDto,String sagaId){
        if (loanApplicationDto == null) {
            return null;
        }
        LoanApplication entity = new LoanApplication();
        entity.setSagaId(sagaId);
        entity.setApplicantName(loanApplicationDto.getApplicantName());
        entity.setApplicantEmail(loanApplicationDto.getApplicantEmail());
        entity.setApplicantPhone(loanApplicationDto.getApplicantPhone());
        entity.setPropertyAddress(loanApplicationDto.getPropertyAddress());
        entity.setLoanAmount(loanApplicationDto.getLoanAmount());
        entity.setApplicationStatus(loanApplicationDto.getApplicationStatus());
        return entity;
    }

/*    void updateEntityFromDto(LoanApplicationDto dto, LoanApplication entity){
        if (dto == null || entity == null) {
            return;
        }

        entity.setApplicantName(dto.getApplicantName());
        entity.setApplicantEmail(dto.getApplicantEmail());
        entity.setApplicantPhone(dto.getApplicantPhone());
        entity.setPropertyAddress(dto.getPropertyAddress());
        entity.setLoanAmount(dto.getLoanAmount());
        entity.setApplicationStatus(dto.getApplicationStatus());
        entity.setUpdatedAt(dto.getUpdatedAt());
    }*/
}
