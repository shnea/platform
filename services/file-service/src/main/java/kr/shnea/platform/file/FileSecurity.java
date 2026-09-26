package kr.shnea.platform.file;

import java.util.*;
import kr.shnea.platform.http.HttpProblems;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class FileSecurity {
    @Bean SecurityFilterChain security(HttpSecurity http, JsonMapper json) throws Exception {
        org.springframework.security.web.AuthenticationEntryPoint unauthorized = (req,res,e) -> {
            res.setHeader("WWW-Authenticate", "Bearer");
            HttpProblems.write("AUTHENTICATION_REQUIRED",401,"관리자 로그인이 필요합니다.",req,res,json);
        };
        org.springframework.security.web.access.AccessDeniedHandler forbidden = (req,res,e) ->
            HttpProblems.write("ACCESS_DENIED",403,"파일을 관리할 권한이 없습니다.",req,res,json);
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String,Object> realm = jwt.getClaim("realm_access");
            return realm != null && realm.get("roles") instanceof Collection<?> roles && roles.contains("platform-admin")
                ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")) : List.of();
        });
        return http.csrf(csrf -> csrf.disable()) // Explicit bearer/API-key only; no authentication cookies.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/files/admin/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers("/api/v1/files", "/api/v1/files/**", "/actuator/health/**", "/internal/v1/monitoring", "/error").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .build();
    }
}
