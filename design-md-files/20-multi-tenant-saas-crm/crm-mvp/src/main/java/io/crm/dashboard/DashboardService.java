package io.crm.dashboard;

import io.crm.contact.ContactRepository;
import io.crm.contact.LeadStatus;
import io.crm.dashboard.dto.DashboardResponse;
import io.crm.deal.DealRepository;
import io.crm.deal.DealStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final ContactRepository contactRepository;
    private final DealRepository dealRepository;

    public DashboardService(ContactRepository contactRepository, DealRepository dealRepository) {
        this.contactRepository = contactRepository;
        this.dealRepository = dealRepository;
    }

    public DashboardResponse getSummary() {
        return new DashboardResponse(
                contactRepository.countByDeletedAtIsNull(),
                contactRepository.countByLeadStatusAndDeletedAtIsNull(LeadStatus.NEW)
                        + contactRepository.countByLeadStatusAndDeletedAtIsNull(LeadStatus.CONTACTED)
                        + contactRepository.countByLeadStatusAndDeletedAtIsNull(LeadStatus.QUALIFIED),
                dealRepository.countByStatusAndDeletedAtIsNull(DealStatus.OPEN),
                dealRepository.countByStatusAndDeletedAtIsNull(DealStatus.WON),
                dealRepository.countByStatusAndDeletedAtIsNull(DealStatus.LOST),
                dealRepository.sumOpenPipelineValue()
        );
    }
}
