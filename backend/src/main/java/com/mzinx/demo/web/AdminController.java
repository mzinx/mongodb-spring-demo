package com.mzinx.demo.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only helper endpoints. Guarded by HTTP Basic auth in
 * {@link com.mzinx.demo.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /**
     * Cheap credential check used by the frontend Admin login gate: reaching
     * this handler at all means the supplied Basic credentials were accepted
     * (otherwise Spring Security returns 401 before we get here).
     */
    @GetMapping("/verify")
    public ResponseEntity<Void> verify() {
        return ResponseEntity.noContent().build();
    }
}
