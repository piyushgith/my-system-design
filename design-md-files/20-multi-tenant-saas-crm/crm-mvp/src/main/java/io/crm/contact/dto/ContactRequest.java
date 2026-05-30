package io.crm.contact.dto;

import io.crm.contact.LeadStatus;

import java.util.UUID;

public record ContactRequest(
        String firstName,
        String lastName,
        String email,
        String phone,
        String company,
        String notes,
        LeadStatus leadStatus,
        UUID ownerId
) {}
