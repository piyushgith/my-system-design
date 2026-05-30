package io.crm.contact.dto;

import io.crm.contact.LeadStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ContactRequest(
        @NotBlank String firstName,
        String lastName,
        @Email String email,
        String phone,
        String company,
        String notes,
        LeadStatus leadStatus,
        UUID ownerId
) {}
