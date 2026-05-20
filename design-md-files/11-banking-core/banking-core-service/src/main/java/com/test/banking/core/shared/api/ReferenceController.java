package com.test.banking.core.shared.api;

import com.test.banking.core.shared.validation.IfscValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

    @GetMapping("/ifsc/{code}/validate")
    public Map<String, Object> validateIfsc(@PathVariable String code) {
        String normalized = IfscValidator.normalizeAndValidate(code);
        return Map.of(
                "ifsc", normalized,
                "valid", true,
                "message", "IFSC format is valid (RBI master lookup not wired in MVP)"
        );
    }
}
