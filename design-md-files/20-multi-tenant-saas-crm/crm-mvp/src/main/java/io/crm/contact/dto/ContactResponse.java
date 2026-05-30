package io.crm.contact.dto;

import io.crm.contact.Contact;
import io.crm.contact.LeadStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ContactResponse(
        UUID contactId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String company,
        String notes,
        LeadStatus leadStatus,
        UUID ownerId,
        String ownerName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ContactResponse from(Contact c) {
        return new ContactResponse(
                c.getContactId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getCompany(),
                c.getNotes(),
                c.getLeadStatus(),
                c.getOwner() != null ? c.getOwner().getUserId() : null,
                c.getOwner() != null ? c.getOwner().getFullName() : null,
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
