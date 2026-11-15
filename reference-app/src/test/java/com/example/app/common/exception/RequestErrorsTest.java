package com.example.app.common.exception;

import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three shapes of a 400's {@code errors} (RFC 9457): a body value by JSON Pointer, a query or path
 * parameter by name, a rule on the body as a whole at {@code #}. Probe endpoints of this class's own,
 * through the real handler, so every case is here whatever the modules declare.
 */
class RequestErrorsTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(handler())
            .setValidator(validator())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(
                    JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
            .build();

    @Test
    @DisplayName("A body field is named by its JSON Pointer, with pointer and detail only")
    void bodyFieldIsAPointer() throws Exception {
        postBody("/probe/body", """
                {"email": " "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0]", aMapWithSize(2)))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/email"))
                .andExpect(jsonPath("$.errors[0].detail").value("must not be blank"));
    }

    @Test
    @DisplayName("Nested objects, list elements and map keys each add a token; a key's / and ~ are escaped")
    void nestedAndIndexedPointers() throws Exception {
        postBody("/probe/body", """
                {"email": "a@example.com",
                 "address": {"city": ""},
                 "items": [{"name": "ok"}, {"name": ""}],
                 "labels": {"a/b~c": ""}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer", containsInAnyOrder("#/address/city", "#/items/1/name", "#/labels/a~1b~0c")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must not be blank", "must not be blank", "must not be blank")));
    }

    @Test
    @DisplayName("A cross-field rule reported on a field points to that field; one on no field points to the body (#)")
    void classLevelRules() throws Exception {
        postBody("/probe/body", """
                {"email": "a@example.com", "startsOn": "2026-11-20", "endsOn": "2026-11-19"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/endsOn")))
                .andExpect(jsonPath("$.errors[*].detail", contains("endsOn must not be before startsOn")));
        postBody("/probe/period", """
                {"startsOn": "2026-11-20", "endsOn": "2026-11-19"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].pointer", contains("#")))
                .andExpect(jsonPath("$.errors[*].detail", contains("endsOn must not be before startsOn")));
    }

    @Test
    @DisplayName("A bound query object's field is a parameter; an unconvertible one is REQUEST_PARAMETER_INVALID naming it")
    void queryObjectFieldsAreParameters() throws Exception {
        mvc.perform(get("/probe/filter").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0]", aMapWithSize(2)))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be less than or equal to 100"));
        mvc.perform(get("/probe/filter").param("size", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("invalid value"));
    }

    @Test
    @DisplayName("@RequestParam: a constraint, a type mismatch and a missing value each name the parameter")
    void requestParameters() throws Exception {
        mvc.perform(get("/probe/limit").param("limit", "11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("limit"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be less than or equal to 10"));
        mvc.perform(get("/probe/id").param("id", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("id"))
                .andExpect(jsonPath("$.errors[0].detail").value("invalid value"));
        mvc.perform(get("/probe/id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_MISSING"))
                .andExpect(jsonPath("$.errors[0].parameter").value("id"));
    }

    @Test
    @DisplayName("Method validation with a parameter and a body at once keeps both shapes")
    void parameterAndBodyTogether() throws Exception {
        mvc.perform(post("/probe/both").param("limit", "11").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("limit")))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/email")));
    }

    @Test
    @DisplayName("A body value Jackson can't read is REQUEST_UNREADABLE pointing to it; broken JSON names nothing")
    void unreadableValuesPointToTheValue() throws Exception {
        postBody("/probe/body", """
                {"email": "a@example.com", "items": [{"name": "ok", "count": "many"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/items/0/count"))
                .andExpect(jsonPath("$.errors[0].detail").value("invalid value"));
        postBody("/probe/body", """
                {"email": "a@example.com", "nickname": "x"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/nickname"))
                .andExpect(jsonPath("$.errors[0].detail").value("unknown field"));
        postBody("/probe/body", "{\"email\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    private ResultActions postBody(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static GlobalExceptionHandler handler() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ErrorCode code : ErrorCode.values()) {
            messages.addMessage(code.name(), Locale.ENGLISH, code.name());
        }
        RetryAfterProperties.Range range = new RetryAfterProperties.Range(Duration.ofSeconds(1), Duration.ofSeconds(3));
        return new GlobalExceptionHandler(messages, new ProblemTypes("reference"),
                new RetryAfterProperties(range, range, range,
                        // media:begin
                        range,
                        // media:end
                        range), new ConstraintViolationCodes(List.of()));
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    @RestController
    static class ProbeController {

        @PostMapping("/probe/body")
        void body(@RequestBody @Valid ProbeBody body) {
        }

        @PostMapping("/probe/period")
        void period(@RequestBody @Valid ProbePeriod period) {
        }

        @GetMapping("/probe/filter")
        void filter(@Valid ProbeFilter filter) {
        }

        @GetMapping("/probe/limit")
        void limit(@RequestParam @Max(10) int limit) {
        }

        @GetMapping("/probe/id")
        void id(@RequestParam UUID id) {
        }

        @PostMapping("/probe/both")
        void both(@RequestParam @Max(10) int limit, @RequestBody @Valid ProbeBody body) {
        }
    }

    @OrderedDates(reportOn = "endsOn")
    record ProbeBody(@NotBlank String email, @Valid ProbeAddress address, List<@Valid ProbeItem> items,
                     Map<String, @NotBlank String> labels, LocalDate startsOn, LocalDate endsOn) implements DateRange {
    }

    record ProbeAddress(@NotBlank String city) {
    }

    record ProbeItem(@NotBlank String name, @Positive Integer count) {
    }

    @OrderedDates
    record ProbePeriod(LocalDate startsOn, LocalDate endsOn) implements DateRange {
    }

    record ProbeFilter(@Max(100) Integer size) {
    }

    interface DateRange {
        LocalDate startsOn();

        LocalDate endsOn();
    }

    /** endsOn not before startsOn, reported on the field {@code reportOn} names, or on the object itself. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = OrderedDatesValidator.class)
    @interface OrderedDates {
        String message() default "endsOn must not be before startsOn";

        String reportOn() default "";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class OrderedDatesValidator implements ConstraintValidator<OrderedDates, DateRange> {

        private String reportOn;

        @Override
        public void initialize(OrderedDates annotation) {
            reportOn = annotation.reportOn();
        }

        @Override
        public boolean isValid(DateRange range, ConstraintValidatorContext context) {
            if (Objects.isNull(range.startsOn()) || Objects.isNull(range.endsOn()) || !range.endsOn().isBefore(range.startsOn())) {
                return true;
            }
            if (!reportOn.isEmpty()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode(reportOn)
                        .addConstraintViolation();
            }
            return false;
        }
    }
}
