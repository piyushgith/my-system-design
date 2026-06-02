package com.pastebin.paste.api;

import com.pastebin.paste.application.PasteService;
import com.pastebin.shared.UserId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class RawPasteController {

    private final PasteService pasteService;

    public RawPasteController(PasteService pasteService) {
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
