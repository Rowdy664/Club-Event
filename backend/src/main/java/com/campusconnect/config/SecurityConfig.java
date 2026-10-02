package com.campusconnect.config;

import com.campusconnect.security.CustomUserDetailsService;
import com.campusconnect.security.JwtAuthEntryPoint;
import com.campusconnect.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;
    private final CustomUserDetailsService userDetailsService;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Public browse endpoints (list+search / featured / detail / schedule).
                        // Search is query-params on the list route (GET /api/events?q=...), so there
                        // is no separate /search path to expose here.
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/featured",
                                "/api/events/*", "/api/events/*/schedule",
                                "/api/events/*/calendar.ics").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/clubs", "/api/clubs/*").permitAll()
                        // Global search is public — anonymous visitors see published events and
                        // active clubs; user matches are gated to admins inside the service.
                        .requestMatchers(HttpMethod.GET, "/api/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/certificates/verify/*",
                                "/api/certificates/verify/*/download").permitAll()
                        // Locally-stored files (banners, avatars, media) are served publicly; the
                        // opaque UUID key is the only handle and uploads stay authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                        // Public competition browse + live leaderboard (reads only)
                        .requestMatchers(HttpMethod.GET, "/api/competitions/event/*", "/api/competitions/*",
                                "/api/competitions/*/rounds", "/api/competitions/*/leaderboard").permitAll()
                        // Public aggregate feedback summary (individual responses stay protected)
                        .requestMatchers(HttpMethod.GET, "/api/feedback/event/*/summary").permitAll()
                        // Public announcement reads (posting/deleting stay protected)
                        .requestMatchers(HttpMethod.GET, "/api/announcements", "/api/announcements/general",
                                "/api/announcements/club/*", "/api/announcements/event/*").permitAll()
                        // Public media gallery reads (adding/deleting stay protected)
                        .requestMatchers(HttpMethod.GET, "/api/media/event/*", "/api/media/club/*").permitAll()
                        // Public event discussion reads (posting/editing/moderating stay protected)
                        .requestMatchers(HttpMethod.GET, "/api/comments/event/*").permitAll()
                        // Public one-click unsubscribe — the opaque token in the email link is the
                        // only credential; every other notification endpoint stays authenticated.
                        .requestMatchers(HttpMethod.POST, "/api/notifications/unsubscribe").permitAll()
                        // Full-platform admin surface — defence-in-depth alongside the
                        // class-level @PreAuthorize("hasRole('ADMIN')") on AdminController.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        // VOLUNTEER sits outside the coordinator management chain: it inherits
        // STUDENT (browse/register/attend) but grants no CLUB_MEMBER/coordinator
        // powers. Volunteer-only endpoints are gated with hasRole('VOLUNTEER').
        return RoleHierarchyImpl.fromHierarchy(
                "ROLE_ADMIN > ROLE_CLUB_COORDINATOR\n" +
                "ROLE_CLUB_COORDINATOR > ROLE_CLUB_MEMBER\n" +
                "ROLE_CLUB_MEMBER > ROLE_STUDENT\n" +
                "ROLE_VOLUNTEER > ROLE_STUDENT");
    }

    /** Applies the role hierarchy to @PreAuthorize method security. */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        // When any entry is a wildcard/pattern (e.g. "*" or "https://*.vercel.app"),
        // use allowedOriginPatterns — setAllowedOrigins("*") together with
        // allowCredentials(true) is rejected by Spring at runtime and would break
        // every cross-origin call in a split-domain deployment.
        boolean hasPattern = origins.stream().anyMatch(o -> o.contains("*"));
        if (hasPattern) {
            configuration.setAllowedOriginPatterns(origins);
        } else {
            configuration.setAllowedOrigins(origins);
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
