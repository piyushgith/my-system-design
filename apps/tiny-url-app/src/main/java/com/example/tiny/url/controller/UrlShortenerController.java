package com.example.tiny.url.controller;

import com.example.tiny.url.entiry.ShortenedUrl;
import com.example.tiny.url.exception.ShortURLNotFoundException;
import com.example.tiny.url.service.ShortenedUrlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Optional;

@RestController("/api")
public class UrlShortenerController {

    @Autowired
    private ShortenedUrlService urlService;

    @PostMapping("/shorten")
    public String shortenUrl(@RequestBody String longUrl) {
        return urlService.shortenUrl(longUrl);
    }

    // Permanent Redirect (301) Example
    @GetMapping("/s/{shortCode}")
    public ResponseEntity getLongUrl(@PathVariable String shortCode) {
        String longUrl = urlService.getLongUrlByShortCode(shortCode).get().getLongUrl();
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header("Location", longUrl).build();
    }

    //this one also works fine
/*    @GetMapping("/s/{shortCode}")
    public RedirectView redirectToLongUrl(@PathVariable String shortCode) {

        return urlService.getLongUrlByShortCode(shortCode).map(shortenedUrl -> {
            // Success: Long URL found (from Redis or DB)
            // Use RedirectView to issue an HTTP 302 Found response
            RedirectView redirectView = new RedirectView(shortenedUrl.getLongUrl());
            redirectView.setStatusCode(org.springframework.http.HttpStatus.FOUND); // 302 is common

            // Optional: For permanent links, use 301 (MOVED_PERMANENTLY)
            // redirectView.setStatusCode(org.springframework.http.HttpStatus.MOVED_PERMANENTLY);

            return redirectView;
        }).orElseGet(() -> {
            // Failure: Short code not found, redirect to an error page or homepage
            RedirectView errorView = new RedirectView("/404");
            errorView.setStatusCode(org.springframework.http.HttpStatus.NOT_FOUND); // 404
            return errorView;
        });
    }*/
}
