package io.crm.deal;

import io.crm.common.PageResponse;
import io.crm.contact.Contact;
import io.crm.contact.ContactRepository;
import io.crm.deal.dto.DealRequest;
import io.crm.deal.dto.DealResponse;
import io.crm.deal.dto.StageTransitionRequest;
import io.crm.user.User;
import io.crm.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DealService {

    private final DealRepository dealRepository;
    private final PipelineRepository pipelineRepository;
    private final StageRepository stageRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;

    public DealService(DealRepository dealRepository, PipelineRepository pipelineRepository,
                       StageRepository stageRepository, ContactRepository contactRepository,
                       UserRepository userRepository) {
        this.dealRepository = dealRepository;
        this.pipelineRepository = pipelineRepository;
        this.stageRepository = stageRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
    }

    public PageResponse<DealResponse> list(DealStatus status, UUID ownerId, UUID pipelineId, int page, int pageSize) {
        Page<Deal> result = dealRepository.findActive(
                status, ownerId, pipelineId,
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result.getContent().stream().map(DealResponse::from).toList(),
                result.getTotalElements(), page, pageSize);
    }

    public DealResponse get(UUID id) {
        return DealResponse.from(requireActive(id));
    }

    @Transactional
    public DealResponse create(DealRequest req) {
        Deal deal = new Deal();
        applyRequest(deal, req);
        return DealResponse.from(dealRepository.save(deal));
    }

    @Transactional
    public DealResponse update(UUID id, DealRequest req) {
        Deal deal = requireActive(id);
        applyRequest(deal, req);
        if (req.status() != null && req.status() != DealStatus.OPEN && deal.getClosedAt() == null) {
            deal.setClosedAt(OffsetDateTime.now());
        }
        return DealResponse.from(dealRepository.save(deal));
    }

    @Transactional
    public DealResponse moveStage(UUID id, StageTransitionRequest req) {
        Deal deal = requireActive(id);
        Stage newStage = stageRepository.findById(req.stageId())
                .orElseThrow(() -> new NoSuchElementException("Stage not found: " + req.stageId()));
        if (!newStage.getPipeline().getPipelineId().equals(deal.getPipeline().getPipelineId())) {
            throw new IllegalArgumentException("Stage does not belong to deal's pipeline");
        }
        deal.setStage(newStage);
        return DealResponse.from(dealRepository.save(deal));
    }

    @Transactional
    public void delete(UUID id) {
        Deal deal = requireActive(id);
        deal.setDeletedAt(OffsetDateTime.now());
        dealRepository.save(deal);
    }

    private Deal requireActive(UUID id) {
        return dealRepository.findActiveById(id)
                .orElseThrow(() -> new NoSuchElementException("Deal not found: " + id));
    }

    private void applyRequest(Deal deal, DealRequest req) {
        deal.setTitle(req.title());
        deal.setValue(req.value());
        deal.setCurrency(req.currency() != null ? req.currency() : "USD");
        deal.setExpectedCloseDate(req.expectedCloseDate());

        Pipeline pipeline = pipelineRepository.findById(req.pipelineId())
                .orElseThrow(() -> new NoSuchElementException("Pipeline not found: " + req.pipelineId()));
        deal.setPipeline(pipeline);

        Stage stage = stageRepository.findById(req.stageId())
                .orElseThrow(() -> new NoSuchElementException("Stage not found: " + req.stageId()));
        if (!stage.getPipeline().getPipelineId().equals(pipeline.getPipelineId())) {
            throw new IllegalArgumentException("Stage does not belong to the specified pipeline");
        }
        deal.setStage(stage);

        User owner = userRepository.findById(req.ownerId())
                .orElseThrow(() -> new NoSuchElementException("Owner not found: " + req.ownerId()));
        deal.setOwner(owner);

        if (req.contactId() != null) {
            Contact contact = contactRepository.findActiveById(req.contactId())
                    .orElseThrow(() -> new NoSuchElementException("Contact not found: " + req.contactId()));
            deal.setContact(contact);
        } else {
            deal.setContact(null);
        }

        if (req.status() != null) {
            deal.setStatus(req.status());
        }
    }
}
