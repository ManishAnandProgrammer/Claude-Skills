package com.example.app.config;

import com.example.app.common.exception.ErrorCode;
// roles:begin
// import com.example.app.common.exception.TransientDatabaseFailure;
// roles:end
import com.example.app.common.logging.UserLogContextFilter;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
// session-version:begin
import com.example.app.common.security.SessionVersionFilter;
import com.example.app.common.security.SessionVersions;
// session-version:end
import com.example.app.common.security.TokenPermissions;
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
// roles:begin
// import org.springframework.security.core.AuthenticationException;
// roles:end
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
// roles:begin
// import org.springframework.security.oauth2.jwt.Jwt;
// roles:end
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
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
import java.util.ArrayList;
import java.util.List;
// roles:begin
// import java.util.Optional;
// import java.util.Set;
// roles:end

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            // One segment, never **: an unknown version is refused 400 by MVC before any handler runs, and a
            // version grants no access by itself.
            "/api/*/auth/**",
            "/actuator/health",
            "/actuator/health/**",
            // No token: served only on the management port, which StartupChecks refuses to share.
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };
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
                        // An exception that escapes to the container's error page isn't re-answered as a 401.
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
        // roles:begin
        // return (request, response, exception) -> problemResponses.write(request, response,
        //         exception instanceof PermissionsUnreadable unreadable ? unreadable.errorCode() : ErrorCode.AUTHENTICATION_REQUIRED);
        // roles:else
        return (request, response, exception) ->
                problemResponses.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED);
        // roles:end
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
    JwtAuthenticationConverter jwtAuthenticationConverter(TokenPermissions tokenPermissions) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // roles:begin
        // converter.setJwtGrantedAuthoritiesConverter(token -> authoritiesOf(readPermissions(tokenPermissions, token)));
        // roles:else
        converter.setJwtGrantedAuthoritiesConverter(token -> authoritiesOf(tokenPermissions.of(token)));
        // roles:end
        return converter;
    }
    // roles:begin

    // /**
    //  * A lock wait, a timeout or no connection reading the permissions is the request's 503, written by the entry
    //  * point, never a 401 (the client would sign the person out) nor a 500.
    //  */
    // private static Set<Permission> readPermissions(TokenPermissions tokenPermissions, Jwt token) {
    //     try {
    //         return tokenPermissions.of(token);
    //     } catch (RuntimeException failure) {
    //         Optional<TransientDatabaseFailure> outage = TransientDatabaseFailure.of(failure);
    //         if (outage.isEmpty()) {
    //             throw failure;
    //         }
    //         throw new PermissionsUnreadable(outage.get().errorCode(), failure);
    //     }
    // }

    // static final class PermissionsUnreadable extends AuthenticationException {

    //     private static final long serialVersionUID = 1L;

    //     private final ErrorCode errorCode;

    //     PermissionsUnreadable(ErrorCode errorCode, Throwable cause) {
    //         super("The caller's permissions couldn't be read: " + errorCode, cause);
    //         this.errorCode = errorCode;
    //     }

    //     ErrorCode errorCode() {
    //         return errorCode;
    //     }
    // }
    // roles:end

    private static List<GrantedAuthority> authoritiesOf(Iterable<Permission> permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Permission permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission.name()));
        }
        return authorities;
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
        // Credentials are never allowed: auth is a header, not a cookie.
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Ahead of admission control ({@code DEFAULT_FILTER_ORDER - 10}) and the security chain: a 503 refused at the
     * door carries {@code Access-Control-Allow-Origin}, and a preflight takes no admission permit.
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
