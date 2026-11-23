package com.example.app.common.limits;

import com.example.app.common.pagination.Cursor;
import com.example.app.common.validation.MaxUtf8Bytes;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.pagination.PageFilter;
import com.example.app.support.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LimitsIntegrationTest extends IntegrationTest {

    private static final List<String> CURSOR_TEXT_KEYS = Stream.of(
            // sign-in:begin
            // tenancy:begin
            // tenancy:else
            List.of("user.full-name"),
            // tenancy:end
            // sign-in:end
            // example:begin
            List.of("todo-list.name", "tag.name", "todo.title"),
            // example:end
            List.<String>of()).flatMap(List::stream).toList();

    private static final String LONGEST_SORT = "A_SORT_NAME_OF_THIRTY_LETTERS_";

    @Autowired
    private Limits limits;

    @Autowired
    private Validator validator;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("""
            Every @MaxLength (on a field or a collection's elements), @ConfiguredMax (on a field or a getter), \
            @ConfiguredMaxSize, @LatestDate and @EarliestDate key in the code, and @NotAfterToday's, has a configured value""")
    void everyLimitKeyIsConfigured() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.app");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (JavaField field : classes.stream().flatMap(javaClass -> javaClass.getFields().stream()).toList()) {
            for (MaxLength elementLimit : elementMaxLengths(field)) {
                checked++;
                checkConfigured(missing, "text." + elementLimit.value(), () -> limits.maxLength(elementLimit.value()));
            }
            if (field.isAnnotatedWith(MaxLength.class)) {
                checked++;
                checkConfigured(missing, "text." + field.getAnnotationOfType(MaxLength.class).value(),
                        () -> limits.maxLength(field.getAnnotationOfType(MaxLength.class).value()));
            }
            if (field.isAnnotatedWith(ConfiguredMax.class)) {
                checked++;
                checkConfigured(missing, field.getAnnotationOfType(ConfiguredMax.class).value(),
                        maximumOf(field.getRawType().isEquivalentTo(BigDecimal.class), field.getAnnotationOfType(ConfiguredMax.class)));
            }
            if (field.isAnnotatedWith(ConfiguredMaxSize.class)) {
                checked++;
                checkConfigured(missing, field.getAnnotationOfType(ConfiguredMaxSize.class).value(),
                        () -> limits.get(field.getAnnotationOfType(ConfiguredMaxSize.class).value()));
            }
            if (field.isAnnotatedWith(LatestDate.class)) {
                checked++;
                String key = "latest-date." + field.getAnnotationOfType(LatestDate.class).value();
                checkConfigured(missing, key, () -> requireDate("app.limits." + key));
            }
            if (field.isAnnotatedWith(EarliestDate.class)) {
                checked++;
                String key = "earliest-date." + field.getAnnotationOfType(EarliestDate.class).value();
                checkConfigured(missing, key, () -> requireDate("app.limits." + key));
            }
        }

        for (JavaMethod method : classes.stream().flatMap(javaClass -> javaClass.getMethods().stream()).toList()) {
            if (method.isAnnotatedWith(ConfiguredMax.class)) {
                checked++;
                checkConfigured(missing, method.getAnnotationOfType(ConfiguredMax.class).value(),
                        maximumOf(method.getRawReturnType().isEquivalentTo(BigDecimal.class), method.getAnnotationOfType(ConfiguredMax.class)));
            }
        }

        assertThat(checked).isPositive();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("""
            Every text field of every request and filter is bounded (@MaxLength, or a @Pattern that bounds the \
            length), and every collection has a configured size and bounded text elements""")
    void everyRequestFieldIsBounded() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.app");
        List<String> unbounded = new ArrayList<>();
        int checked = 0;
        for (JavaField field : classes.stream().flatMap(javaClass -> javaClass.getFields().stream()).toList()) {
            if (field.getModifiers().contains(JavaModifier.STATIC) || !field.getOwner().getPackageName().contains(".dto.request")) {
                continue;
            }
            if (field.getRawType().isEquivalentTo(String.class)) {
                checked++;
                if (!field.isAnnotatedWith(MaxLength.class) && !hasALengthBoundingPattern(field) && !isBoundInBytes(field)) {
                    unbounded.add(field.getFullName() + " (no @MaxLength)");
                }
            }
            if (field.getRawType().isAssignableTo(java.util.Collection.class)) {
                checked++;
                if (!field.isAnnotatedWith(ConfiguredMaxSize.class)) {
                    unbounded.add(field.getFullName() + " (no @ConfiguredMaxSize)");
                }
                if (field.reflect().getGenericType().getTypeName().endsWith("<java.lang.String>") && elementMaxLengths(field).isEmpty()) {
                    unbounded.add(field.getFullName() + " (elements without @MaxLength)");
                }
            }
        }

        assertThat(checked).isPositive();
        assertThat(unbounded).as("request fields without a configured bound").isEmpty();
    }

    private static boolean isBoundInBytes(JavaField field) {
        return field.isAnnotatedWith(MaxUtf8Bytes.class) || field.isMetaAnnotatedWith(MaxUtf8Bytes.class);
    }

    private static boolean hasALengthBoundingPattern(JavaField field) {
        if (!field.isAnnotatedWith(jakarta.validation.constraints.Pattern.class)) {
            return false;
        }
        String regexp = field.getAnnotationOfType(jakarta.validation.constraints.Pattern.class).regexp();
        return !regexp.contains("*") && !regexp.contains("+") && !regexp.matches(".*\\{\\d+,}.*");
    }

    /** Limits on a collection's elements ({@code Set<@MaxLength("…") String>}), which field annotations don't include. */
    private static List<MaxLength> elementMaxLengths(JavaField field) {
        List<MaxLength> found = new ArrayList<>();
        if (field.reflect().getAnnotatedType() instanceof AnnotatedParameterizedType parameterized) {
            for (java.lang.reflect.AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
                MaxLength maxLength = argument.getAnnotation(MaxLength.class);
                if (Objects.nonNull(maxLength)) {
                    found.add(maxLength);
                }
            }
        }
        return found;
    }

    private Runnable maximumOf(boolean decimal, ConfiguredMax limit) {
        return decimal ? () -> limits.getDecimal(limit.value()) : () -> limits.getLong(limit.value());
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

    @Test
    @DisplayName("""
            The cursor limit holds a cursor taken after the longest value of every text key a cursor carries, \
            control characters (escaped in the cursor's JSON) included""")
    void cursorLimitHoldsTheLongestTextKeys() {
        int cursorLimit = limits.maxLength("cursor");
        for (String key : CURSOR_TEXT_KEYS) {
            // \u0001 is the worst character: JSON writes it as six bytes.
            String longest = "\u0001".repeat(limits.maxLength(key));
            String cursor = Cursor.write(LONGEST_SORT, longest, UUID.randomUUID());

            assertThat(cursor.length()).as("a cursor after the longest " + key).isLessThanOrEqualTo(cursorLimit);
        }
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

    /** A key every project has (search-query: 100). */
    record SomeTextRequest(@MaxLength("search-query") String text) {
    }

    @Test
    @DisplayName("A NUL character fails validation (PostgreSQL text refuses it), at any length")
    void nulCharacterFailsValidation() {
        assertThat(validator.validate(new SomeTextRequest("a\u0000b"))).extracting(ConstraintViolation::getMessage)
                .containsExactly("must not contain the NUL character");
        assertThat(validator.validate(new SomeTextRequest("a b"))).isEmpty();
    }

    @Test
    @DisplayName("A lone surrogate fails validation (UTF-8 can't carry half of a character); a whole pair passes")
    void loneSurrogateFailsValidation() {
        assertThat(validator.validate(new SomeTextRequest("a\ud83db"))).extracting(ConstraintViolation::getMessage)
                .containsExactly("must not contain half of a character (a lone surrogate)");
        assertThat(validator.validate(new SomeTextRequest("a\ud83d\ude00b"))).isEmpty();
    }

    /** A key every project has (offset-page.max-size: 100). */
    record SomeBulkRequest(@ConfiguredMaxSize("offset-page.max-size") List<Integer> items) {
    }

    @Test
    @DisplayName("A collection of the configured maximum size is valid; one element more fails, naming the limit")
    void collectionSizeLimitComesFromConfiguration() {
        int max = limits.get("offset-page.max-size");
        List<Integer> full = IntStream.range(0, max).boxed().toList();
        List<Integer> overfull = IntStream.rangeClosed(0, max).boxed().toList();

        assertThat(validator.validate(new SomeBulkRequest(full))).isEmpty();
        assertThat(validator.validate(new SomeBulkRequest(null))).isEmpty();
        assertThat(validator.validate(new SomeBulkRequest(overfull))).extracting(ConstraintViolation::getMessage)
                .containsExactly("must have at most 100 items");
    }

    @Test
    @DisplayName("The API docs show a configured collection size as maxItems")
    void apiDocsShowTheConfiguredCollectionSize() throws Exception {
        Annotation limit = SomeBulkRequest.class.getDeclaredField("items").getAnnotation(ConfiguredMaxSize.class);

        LimitsOpenApiCustomizer customizer = new LimitsOpenApiCustomizer(limits);
        Schema<?> collection = customizer.customize(new ArraySchema().items(new IntegerSchema()),
                new AnnotatedType().ctxAnnotations(new Annotation[]{limit}));
        Schema<?> element = customizer.customize(new IntegerSchema(), new AnnotatedType().ctxAnnotations(new Annotation[]{limit}));

        assertThat(collection.getMaxItems()).isEqualTo(100);
        assertThat(collection.getMaximum()).isNull();
        assertThat(element.getMaxItems()).as("swagger also passes the field's annotations for the items").isNull();
    }

    record SomeAmountRequest(@ConfiguredMax("probe.max-amount") BigDecimal amount) {
    }

    @Test
    @DisplayName("""
            A decimal at the configured maximum is valid at any scale; a hundredth more fails, naming the limit as \
            configured""")
    void decimalMaximumComesFromConfiguration() {
        assertThat(validator.validate(new SomeAmountRequest(new BigDecimal("10000.50")))).isEmpty();
        assertThat(validator.validate(new SomeAmountRequest(new BigDecimal("10000.5000")))).isEmpty();
        assertThat(validator.validate(new SomeAmountRequest(null))).isEmpty();
        assertThat(validator.validate(new SomeAmountRequest(new BigDecimal("10000.51"))))
                .extracting(ConstraintViolation::getMessage).containsExactly("must be at most 10000.50");
    }

    @Test
    @DisplayName("The API docs show a configured decimal maximum as the number's maximum")
    void apiDocsShowTheConfiguredDecimalMaximum() throws Exception {
        Annotation limit = SomeAmountRequest.class.getDeclaredField("amount").getAnnotation(ConfiguredMax.class);

        Schema<?> amount = new LimitsOpenApiCustomizer(limits).customize(new NumberSchema(),
                new AnnotatedType().ctxAnnotations(new Annotation[]{limit}));

        assertThat(amount.getMaximum()).isEqualByComparingTo("10000.50");
    }

    /** A key every project has (search-query: 100). */
    record SomeKeysRequest(Set<@MaxLength("search-query") String> keys) {
    }

    @Test
    @DisplayName("An element @MaxLength validates each element, naming the limit")
    void elementLengthLimitComesFromConfiguration() {
        assertThat(validator.validate(new SomeKeysRequest(Set.of("k".repeat(100))))).isEmpty();
        assertThat(validator.validate(new SomeKeysRequest(Set.of("k".repeat(101))))).extracting(ConstraintViolation::getMessage)
                .containsExactly("must be at most 100 characters");
    }

    @Test
    @DisplayName("The API docs show an element @MaxLength as the items' maxLength")
    void apiDocsShowTheConfiguredElementLength() {
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new ElementLimitsModelConverter(limits));

        Map<String, Schema> models = converters.readAll(SomeKeysRequest.class);

        Schema<?> keys = (Schema<?>) models.get("SomeKeysRequest").getProperties().get("keys");
        assertThat(keys.getItems().getMaxLength()).isEqualTo(100);
    }
}
