package com.example.app.config;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.logging.UserLogContextFilter;
import com.example.app.common.security.CurrentUser;
// session-version:begin
import com.example.app.common.security.SessionVersionFilter;
import com.example.app.common.security.SessionVersions;
// session-version:end
import com.example.app.common.web.ProblemResponses;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stateless JWT security.
 *
 * <ul>
 *   <li>Access tokens are HS256 JWTs signed and verified with {@code app.jwt.secret} by Spring
 *       Security's resource server: checked by signature, expiry and issuer, with no database
 *       lookup per request. The {@code sub} claim is the user id, {@code roles} the roles.</li>
 *   <li>Public: the sign-in endpoints, health, the prometheus scrape (management port only), and
 *       the API docs (disabled in prod). Everything else needs a token. Role checks, when a role
 *       beyond {@code USER} exists, live on the controllers as meta-annotations of
 *       {@code common.security} ({@code @AdminOnly}; method security is on).</li>
 *   <li>{@code DispatcherType.ERROR} is permitted, so an exception that escapes to the container's
 *       error page isn't re-answered as a 401.</li>
 *   <li>Security errors are Problem Details with an {@code errorCode}, like every other error, the
 *       firewall's refusals included (an empty segment {@code //}, a {@code ;}, an encoded {@code .}).</li>
 *   <li>CORS is a servlet filter ahead of admission control and this chain ({@link #corsFilter}), not the
 *       chain's {@code .cors()}: every answer written before the chain (503 {@code SERVER_BUSY}, the
 *       firewall's 400) carries the headers a browser needs to read it. It answers every preflight, so the
 *       chain permits no {@code OPTIONS}: a plain one needs a token like any other request. An origin it
 *       doesn't allow is 403 {@code CORS_REQUEST_REFUSED} ({@link ProblemCorsProcessor}).</li>
 *   <!-- session-version:begin -->
 *   <li>The session version (the session-version piece): a verified token is accepted only while its
 *       {@code sv} claim is current ({@link SessionVersionFilter}), one Redis read per request.</li>
 *   <!-- session-version:end -->
 * </ul>
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            // Every API version's sign-in, so a new version needs no change here. One segment only
            // (never **): an unknown version is refused 400 by MVC before any handler runs, and a
            // version grants no access by itself, since every other path needs a token.
            "/api/*/auth/**",
            "/actuator/health",
            "/actuator/health/**",
            // No token (metricsAuth: network-only): served only on the management port, which is
            // never published publicly; StartupChecks refuses a missing or shared one.
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };
    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String HMAC_SHA_256 = "HmacSHA256";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                            AuthenticationEntryPoint authenticationEntryPoint,
                                            AccessDeniedHandler accessDeniedHandler,
                                            // session-version:begin
                                            SessionVersions sessionVersions,
                                            ProblemResponses problemResponses,
                                            // session-version:end
                                            CurrentUser currentUser) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // A servlet filter ahead of admission control does it (corsFilter): here it would come too late.
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(new UserLogContextFilter(currentUser), BearerTokenAuthenticationFilter.class);
        // session-version:begin
        // Behind the log context, so a refusal's log line names the user.
        http.addFilterAfter(new SessionVersionFilter(sessionVersions, problemResponses), UserLogContextFilter.class);
        // session-version:end
        return http.build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ProblemResponses problemResponses) {
        return (request, response, exception) ->
                problemResponses.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ProblemResponses problemResponses) {
        return (request, response, exception) -> problemResponses.write(request, response, ErrorCode.ACCESS_DENIED);
    }

    /** Without it the firewall's refusal is sent as a bare 400, which Boot's /error answers in its own JSON. */
    @Bean
    RequestRejectedHandler requestRejectedHandler(ProblemResponses problemResponses) {
        return (request, response, rejected) -> problemResponses.write(request, response, ErrorCode.REQUEST_INVALID);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix(ROLE_PREFIX);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // tenancy:begin
        // decoder.setJwtValidator(PlatformSecurityConfig.tenantTokensOnly(JwtValidators.createDefaultWithIssuer(properties.issuer())));
        // tenancy:else
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        // tenancy:end
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey(properties)));
    }

    @Bean
    PasswordEncoder passwordEncoder(PasswordHashProperties properties) {
        return new BCryptPasswordEncoder(properties.passwordHashStrength());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", "X-Request-ID"));
        configuration.setExposedHeaders(List.of("X-Request-ID", "Retry-After", "Location"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Ahead of admission control ({@code DEFAULT_FILTER_ORDER - 10}) and the security chain: a 503 refused at the
     * door carries {@code Access-Control-Allow-Origin} and the exposed {@code Retry-After}, and a preflight is
     * answered here without taking an admission permit.
     */
    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(CorsConfigurationSource corsConfigurationSource,
                                                  ProblemResponses problemResponses) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor(new ProblemCorsProcessor(problemResponses));
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 20);
        return registration;
    }

    static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
    }
}
