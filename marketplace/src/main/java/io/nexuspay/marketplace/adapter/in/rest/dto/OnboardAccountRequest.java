package io.nexuspay.marketplace.adapter.in.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record OnboardAccountRequest(
        @NotBlank String businessName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotBlank @Size(min = 3, max = 3) String defaultCurrency,
        String payoutSchedule
) {}
