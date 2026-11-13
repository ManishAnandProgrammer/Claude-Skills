package com.example.app.common.limits;

import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.pagination.PageFilter;
import com.example.app.support.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LimitsIntegrationTest extends IntegrationTest {

    @Autowired
    private Limits limits;

    @Autowired
    private Validator validator;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Every @MaxLength, @ConfiguredMax and @LatestDate key in the code has a configured value")
    void everyLimitKeyIsConfigured() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.app");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (JavaField field : classes.stream().flatMap(javaClass -> javaClass.getFields().stream()).toList()) {
            if (field.isAnnotatedWith(MaxLength.class)) {
                checked++;
                checkConfigured(missing, "text." + field.getAnnotationOfType(MaxLength.class).value(),
                        () -> limits.maxLength(field.getAnnotationOfType(MaxLength.class).value()));
            }
            if (field.isAnnotatedWith(ConfiguredMax.class)) {
                checked++;
                checkConfigured(missing, field.getAnnotationOfType(ConfiguredMax.class).value(),
                        () -> limits.get(field.getAnnotationOfType(ConfiguredMax.class).value()));
            }
            if (field.isAnnotatedWith(LatestDate.class)) {
                checked++;
                String key = "latest-date." + field.getAnnotationOfType(LatestDate.class).value();
                checkConfigured(missing, key, () -> requireDate("app.limits." + key));
            }
        }

        assertThat(checked).isPositive();
        assertThat(missing).isEmpty();
    }

    private void requireDate(String property) {
        String value = environment.getProperty(property);
        if (Objects.isNull(value)) {
            throw new IllegalStateException(property + " is not configured");
        }
        LocalDate.parse(value);
    }

    private static void checkConfigured(List<String> missing, String key, Runnable lookup) {
        try {
            lookup.run();
        } catch (IllegalStateException notConfigured) {
            missing.add("app.limits." + key);
        }
    }

    @Test
    @DisplayName("The API docs show a configured length on a request field")
    void apiDocsShowTheConfiguredLimits() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.SignupRequest.properties.fullName.maxLength").value(100));
    }

    static class SomeFilter extends PageFilter {
    }

    static class SomeCursorFilter extends CursorFilter {
    }

    @Test
    @DisplayName("Page index and sizes beyond the configured maximums fail validation, naming the limit")
    void pagingLimitsComeFromConfiguration() {
        SomeFilter page = new SomeFilter();
        page.setPage(100_001);
        page.setSize(101);
        SomeCursorFilter cursor = new SomeCursorFilter();
        cursor.setSize(101);

        assertThat(validator.validate(page)).extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("must be at most 100000", "must be at most 100");
        assertThat(validator.validate(cursor)).extracting(ConstraintViolation::getMessage)
                .containsExactly("must be at most 100");
    }
}
