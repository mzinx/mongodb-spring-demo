package com.mzinx.demo.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security setup for the demo.
 * <p>
 * The whole site is public EXCEPT the <em>mutating</em> operations behind the
 * frontend "Admin" tab, which are guarded by HTTP Basic auth:
 * <ul>
 *   <li>change-stream writes — create/reconfigure, start, stop, delete</li>
 *   <li>pipeline-template writes — save ({@code PUT}) and delete</li>
 *   <li>ad-hoc aggregation execution ({@code POST /api/aggregations/run})</li>
 * </ul>
 * Read-only endpoints (e.g. {@code GET /api/streams/**}, {@code GET /api/pipelines})
 * stay public because the Dashboard and other panels use them, so nothing on
 * the public site is ever prompted for credentials. The single admin credential
 * comes from the {@code ADMIN_USERNAME} / {@code ADMIN_PASSWORD} environment
 * variables.
 */
@Configuration
public class SecurityConfig {

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // Basic-auth admin calls are fully stateless: never create an
                // HTTP session and never persist the SecurityContext. This keeps
                // the authentication out of the MongoDB-backed Spring Session
                // (whose Jackson converter can't serialize the Instant fields on
                // modern GrantedAuthority types) — every request re-authenticates
                // via the Authorization header instead.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(sc -> sc.securityContextRepository(new NullSecurityContextRepository()))
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight requests carry no credentials.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // --- Guarded: mutating Admin-tab operations only ---
                        // Change-stream lifecycle writes.
                        .requestMatchers(HttpMethod.POST, "/api/streams", "/api/streams/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/streams/**").hasRole("ADMIN")
                        // Pipeline-template CRUD (save/delete).
                        .requestMatchers(HttpMethod.PUT, "/api/pipelines/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/pipelines/**").hasRole("ADMIN")
                        // Ad-hoc aggregation execution.
                        .requestMatchers(HttpMethod.POST, "/api/aggregations/**").hasRole("ADMIN")
                        // Credential-check endpoint used by the Admin login gate.
                        .requestMatchers(HttpMethod.GET, "/api/admin/verify").hasRole("ADMIN")
                        // Everything else (incl. read-only GET status endpoints
                        // used by the public Dashboard) stays open.
                        .anyRequest().permitAll())
                // Accept the Authorization: Basic header, but on a failed/missing
                // credential return a bare 401 WITHOUT a "WWW-Authenticate: Basic"
                // header. That header is what makes the browser pop its native
                // login dialog; suppressing it lets the React AdminGate own the
                // login UX instead, so the rest of the site is never prompted.
                .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** Single admin account whose credentials come from the environment. */
    @Bean
    InMemoryUserDetailsManager userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Allows the Vite dev server (different origin) to call the API directly. */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        // Required so the browser will attach the Authorization header on
        // cross-origin (dev-server) requests and expose the auth challenge.
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
