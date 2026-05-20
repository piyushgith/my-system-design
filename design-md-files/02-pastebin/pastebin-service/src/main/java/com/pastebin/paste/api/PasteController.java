package com.pastebin.paste.api;

import com.pastebin.identity.application.AuthService;
import com.pastebin.identity.application.AuthResult;
import com.pastebin.identity.application.LoginCommand;
import com.pastebin.identity.application.RegisterCommand;
import com.pastebin.paste.application.CreatePasteCommand;
import com.pastebin.paste.application.CreatePasteResult;
import com.pastebin.paste.application.PasteListResult;
import com.pastebin.paste.application.PasteService;
import com.pastebin.paste.application.PasteView;
import com.pastebin.shared.UserId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
public class PasteController {

    private final PasteService pasteService;
    private final AuthService authService;

    public PasteController(PasteService pasteService, AuthService authService) {
        this.pasteService = pasteService;
        this.authService = authService;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(new RegisterCommand(
                request.email(), request.password(), request.displayName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(result));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(new LoginCommand(request.email(), request.password()));
        return ResponseEntity.ok(AuthResponse.from(result));
    }

    @PostMapping("/pastes")
    public ResponseEntity<CreatePasteResponse> createPaste(
            @Valid @RequestBody CreatePasteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal UserId userId) {
        CreatePasteResult result = pasteService.createPaste(
                request.toCommand(idempotencyKey),
                Optional.ofNullable(userId)
        );
        HttpStatus status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(CreatePasteResponse.from(result));
    }

    @GetMapping("/pastes/{key}")
    public ResponseEntity<PasteResponse> getPaste(
            @PathVariable String key,
            @RequestHeader(value = "X-Paste-Password", required = false) String password,
            @AuthenticationPrincipal UserId userId) {
        PasteView view = pasteService.getPaste(key, Optional.ofNullable(userId), Optional.ofNullable(password));
        return ResponseEntity.ok(PasteResponse.from(view));
    }

    @DeleteMapping("/pastes/{key}")
    public ResponseEntity<Void> deletePaste(@PathVariable String key, @AuthenticationPrincipal UserId userId) {
        pasteService.deletePaste(key, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me/pastes")
    public ResponseEntity<PasteListResponse> listMyPastes(
            @AuthenticationPrincipal UserId userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean includeExpired) {
        PasteListResult result = pasteService.listUserPastes(userId, cursor, limit, includeExpired);
        return ResponseEntity.ok(PasteListResponse.from(result));
    }

    @GetMapping("/meta/languages")
    public ResponseEntity<Map<String, List<LanguageItem>>> languages() {
        return ResponseEntity.ok(Map.of("languages", LanguageItem.defaults()));
    }
}

@RestController
class RawPasteController {

    private final PasteService pasteService;

    RawPasteController(PasteService pasteService) {
        this.pasteService = pasteService;
    }

    @GetMapping("/raw/{key}")
    public ResponseEntity<String> rawPaste(
            @PathVariable String key,
            @RequestHeader(value = "X-Paste-Password", required = false) String password,
            @AuthenticationPrincipal UserId userId,
            HttpServletRequest request) {
        String content = pasteService.getRawContent(key, Optional.ofNullable(userId), Optional.ofNullable(password));
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Cache-Control", "public, max-age=3600")
                .header("Content-Disposition", "inline; filename=\"" + key + ".txt\"")
                .body(content);
    }
}
