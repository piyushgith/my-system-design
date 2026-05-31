package com.fintech.ledger.api;

import com.fintech.ledger.api.dto.*;
import com.fintech.ledger.api.dto.PostingCreationResult;
import com.fintech.ledger.service.PostingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/postings")
@RequiredArgsConstructor
public class PostingController {

    private final PostingService postingService;

    @PostMapping
    public ResponseEntity<PostingResponse> createPosting(@Valid @RequestBody CreatePostingRequest req) {
        PostingCreationResult result = postingService.createPosting(req);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Result", result.created() ? "CREATED" : "HIT");
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).headers(headers).body(result.response());
    }

    @PostMapping("/{postingId}/reverse")
    public ResponseEntity<PostingResponse> reversePosting(@PathVariable UUID postingId,
                                                           @Valid @RequestBody ReversePostingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postingService.reversePosting(postingId, req));
    }

    @GetMapping("/{postingId}")
    public ResponseEntity<PostingResponse> getPosting(@PathVariable UUID postingId) {
        return ResponseEntity.ok(postingService.getPosting(postingId));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<PostingResponse>> listPostings(
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "2000-01-01T00:00:00Z")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(defaultValue = "2099-12-31T23:59:59Z")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<PostingResponse> result = postingService.listPostings(accountId, from, to, page, size);
        return ResponseEntity.ok(new PagedResponse<>(
                result.getContent(), page, size,
                result.getTotalElements(), result.hasNext()));
    }
}
