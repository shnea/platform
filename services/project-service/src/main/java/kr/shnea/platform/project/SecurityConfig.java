package kr.shnea.platform.project;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, ApiProblems problems) throws Exception {
        org.springframework.security.web.AuthenticationEntryPoint unauthorized = (request, response, error) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            problems.write(ApiCode.AUTHENTICATION_REQUIRED, request, response);
        };
        org.springframework.security.web.access.AccessDeniedHandler forbidden = (request, response, error) ->
            problems.write(ApiCode.ACCESS_DENIED, request, response);
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> access = jwt.getClaim("realm_access");
            if (access != null && access.get("roles") instanceof Collection<?> roles
                    && roles.contains("platform-admin")) {
                return List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
            }
            return List.of();
        });
        return http.csrf(csrf -> csrf.disable()) // Stateless bearer / explicit API-key only; no cookie auth.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/error", "/api/v1/config").permitAll()
                .requestMatchers("/api/v1/integration/context", "/api/v1/dev/login").permitAll() // Controllers validate scoped key.
                .requestMatchers("/internal/v1/email/environments/*").permitAll() // Dedicated internal secret, never routed by Nginx.
                .requestMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .build();
    }
}
