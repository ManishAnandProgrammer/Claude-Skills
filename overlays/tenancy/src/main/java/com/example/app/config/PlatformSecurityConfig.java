package com.example.app.config;

import com.example.app.common.logging.UserLogContextFilter;
import com.example.app.common.security.ActiveStaffFilter;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.StaffAccounts;
import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.web.ProblemResponses;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.List;
import java.util.Objects;

/**
 * Tenancy's two worlds: {@code /api/*}{@code /platform/**} has a chain of its own that accepts only
 * platform tokens ({@code aud=platform}), and {@link SecurityConfig}'s main chain accepts only tenant
 * tokens ({@code aud=tenant}, {@link #tenantTokensOnly}). A token of one plane is a 401 in the other,
 * so a staff token can't reach tenant data by mistake, nor a tenant's token the tenant register. A verified
 * platform token passes only while its staff member is active ({@link ActiveStaffFilter}, one read by primary
 * key per platform request): a deactivated member's tokens die at their next request.
 */
@Configuration
class PlatformSecurityConfig {

    private static final String PLATFORM_PATHS = "/api/*/platform/**";
    private static final String PLATFORM_LOGIN = "/api/*/platform/auth/login";

    /** Before the main chain, which matches every path. */
    @Bean
    @Order(1)
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http,
                                                    JwtProperties properties,
                                                    JwtAuthenticationConverter jwtAuthenticationConverter,
                                                    AuthenticationEntryPoint authenticationEntryPoint,
                                                    AccessDeniedHandler accessDeniedHandler,
                                                    StaffAccounts staffAccounts,
                                                    ProblemResponses problemResponses,
                                                    CurrentUser currentUser) throws Exception {
        http
                .securityMatcher(PLATFORM_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PLATFORM_LOGIN).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(platformDecoder(properties))
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(new UserLogContextFilter(currentUser), BearerTokenAuthenticationFilter.class)
                // Behind the log context, so a refusal's log line names the staff member.
                .addFilterAfter(new ActiveStaffFilter(staffAccounts, problemResponses), UserLogContextFilter.class);
        return http.build();
    }

    /** The main chain's validator, narrowed to tenant tokens. */
    static OAuth2TokenValidator<Jwt> tenantTokensOnly(OAuth2TokenValidator<Jwt> validator) {
        return withAudience(validator, TenantContext.TENANT_AUDIENCE);
    }

    /** Not a bean, so the main chain's decoder stays the only one Boot sees. */
    private static JwtDecoder platformDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(SecurityConfig.secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(withAudience(JwtValidators.createDefaultWithIssuer(properties.issuer()),
                TenantContext.PLATFORM_AUDIENCE));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> withAudience(OAuth2TokenValidator<Jwt> validator, String audience) {
        JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(JwtClaimNames.AUD,
                audiences -> Objects.nonNull(audiences) && audiences.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(validator, audienceValidator);
    }
}
