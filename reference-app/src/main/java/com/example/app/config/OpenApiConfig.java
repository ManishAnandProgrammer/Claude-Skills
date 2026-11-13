package com.example.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The OpenAPI document ({@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}). Endpoint
 * documentation lives on the {@code controller/api/*Api} interfaces. Off by default
 * ({@code springdoc.*.enabled: false} in {@code config/server.yml}), on in the {@code dev} profile. The
 * document's version is the served versions, {@code spring.mvc.apiversion.supported}.
 */
@Configuration
class OpenApiConfig {

    private static final String BEARER = "bearer";

    @Bean
    OpenAPI openApi(@Value("${spring.application.name}") String applicationName,
                    @Value("${spring.mvc.apiversion.supported}") List<String> supportedVersions) {
        return new OpenAPI()
                .info(new Info().title(applicationName).version(String.join(", ", supportedVersions)))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
