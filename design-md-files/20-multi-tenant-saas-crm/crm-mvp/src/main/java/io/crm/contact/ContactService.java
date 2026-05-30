package io.crm.contact;

import io.crm.contact.dto.ContactRequest;
import io.crm.contact.dto.ContactResponse;
import io.crm.common.PageResponse;
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
public class ContactService {

    private final ContactRepository contactRepository;
    private final UserRepository userRepository;

    public ContactService(ContactRepository contactRepository, UserRepository userRepository) {
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
    }

    public PageResponse<ContactResponse> list(LeadStatus leadStatus, UUID ownerId, int page, int pageSize) {
        Page<Contact> result = contactRepository.findActive(
                leadStatus, ownerId,
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result.getContent().stream().map(ContactResponse::from).toList(),
                result.getTotalElements(), page, pageSize);
    }

    public ContactResponse get(UUID id) {
        return ContactResponse.from(requireActive(id));
    }

    @Transactional
    public ContactResponse create(ContactRequest req) {
        Contact contact = new Contact();
        applyRequest(contact, req);
        return ContactResponse.from(contactRepository.save(contact));
    }

    @Transactional
    public ContactResponse update(UUID id, ContactRequest req) {
        Contact contact = requireActive(id);
        applyRequest(contact, req);
        return ContactResponse.from(contactRepository.save(contact));
    }

    @Transactional
    public void delete(UUID id) {
        Contact contact = requireActive(id);
        contact.setDeletedAt(OffsetDateTime.now());
        contactRepository.save(contact);
    }

    private Contact requireActive(UUID id) {
        return contactRepository.findActiveById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
    }

    private void applyRequest(Contact contact, ContactRequest req) {
        contact.setFirstName(req.firstName());
        contact.setLastName(req.lastName());
        contact.setEmail(req.email());
        contact.setPhone(req.phone());
        contact.setCompany(req.company());
        contact.setNotes(req.notes());
        contact.setLeadStatus(req.leadStatus());
        if (req.ownerId() != null) {
            User owner = userRepository.findById(req.ownerId())
                    .orElseThrow(() -> new NoSuchElementException("Owner user not found: " + req.ownerId()));
            contact.setOwner(owner);
        } else {
            contact.setOwner(null);
        }
    }
}
