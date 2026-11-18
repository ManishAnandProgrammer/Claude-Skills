package com.example.app.config;

import com.example.app.common.scheduling.Cron;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.constraints.time.DurationMin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.net.URI;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a settings record binds, on Boot's own binder and conversion service as the application runs them (no Spring
 * context): each claim house-review CFG-12 makes about typed settings. The values are what a yml file or an
 * environment variable holds; the records are this test's, so no setting exists only to show a type.
 */
class SettingsBindingTest {

    private static final String PREFIX = "app.example";
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    enum CodePurpose { SIGNUP_VERIFICATION, LOGIN_SECOND_FACTOR }

    record Spans(Duration lease, Period keepFor, DataSize maxUpload) {
    }

    record CodeLifetimes(Map<CodePurpose, Duration> lifetime) {
    }

    record Address(URI endpoint) {
    }

    record Formats(CodePurpose mode, Locale language, MediaType contentType) {
    }

    record Schedule(Cron cron) {
    }

    record Ttl(@DurationMin(seconds = 1) Duration accessTokenTtl) {
    }

    record Share(Percentage share) {
    }

    /** A value with a grammar and no public constructor: only its {@code of(String)} can make one. */
    static final class Percentage {

        private final int value;

        private Percentage(int value) {
            this.value = value;
        }

        // Public, as Spring's converter needs it: a package-private factory is never found.
        public static Percentage of(String text) {
            if (!text.endsWith("%")) {
                throw new IllegalArgumentException("a percentage ends with %");
            }
            return new Percentage(Integer.parseInt(text.substring(0, text.length() - 1)));
        }
    }

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    private static <T> T bind(Class<T> type, Map<String, String> values) {
        return binder(values).bind(PREFIX, type).get();
    }

    /** As the application binds a {@code @Validated} record: its constraints checked once it is bound. */
    private static <T> T bindValidated(Class<T> type, Map<String, String> values) {
        ValidationBindHandler validation = new ValidationBindHandler(new SpringValidatorAdapter(VALIDATION.getValidator()));
        return binder(values).bind(PREFIX, Bindable.of(type), validation).get();
    }

    private static Binder binder(Map<String, String> values) {
        return new Binder(List.of(new MapConfigurationPropertySource(values)), null, ApplicationConversionService.getSharedInstance());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"500ms, PT0.5S", "30s, PT30S", "5m, PT5M", "6h, PT6H", "7d, PT168H", "PT15M, PT15M"})
    @DisplayName("A Duration takes a number with its unit, or ISO-8601")
    void durationsTakeTheirUnit(String text, Duration expected) {
        assertThat(bind(Spans.class, Map.of(PREFIX + ".lease", text)).lease()).isEqualTo(expected);
    }

    @Test
    @DisplayName("A Period takes days, weeks and years; a DataSize KB and MB (1024 bytes a KB)")
    void periodsAndSizesTakeTheirUnit() {
        assertThat(bind(Spans.class, Map.of(PREFIX + ".keep-for", "7d")).keepFor()).isEqualTo(Period.ofDays(7));
        assertThat(bind(Spans.class, Map.of(PREFIX + ".keep-for", "2w")).keepFor()).isEqualTo(Period.ofWeeks(2));
        assertThat(bind(Spans.class, Map.of(PREFIX + ".keep-for", "1y")).keepFor()).isEqualTo(Period.ofYears(1));
        assertThat(bind(Spans.class, Map.of(PREFIX + ".max-upload", "512KB")).maxUpload()).isEqualTo(DataSize.ofBytes(512 * 1024));
        assertThat(bind(Spans.class, Map.of(PREFIX + ".max-upload", "2MB")).maxUpload()).isEqualTo(DataSize.ofMegabytes(2));
    }

    @Test
    @DisplayName("The migration trap: a bare number is milliseconds for a Duration, days for a Period, bytes for a DataSize")
    void aBareNumberHasADefaultUnit() {
        Spans spans = bind(Spans.class, Map.of(PREFIX + ".lease", "15", PREFIX + ".keep-for", "15", PREFIX + ".max-upload", "15"));

        assertThat(spans.lease()).isEqualTo(Duration.ofMillis(15));
        assertThat(spans.keepFor()).isEqualTo(Period.ofDays(15));
        assertThat(spans.maxUpload()).isEqualTo(DataSize.ofBytes(15));
    }

    @Test
    @DisplayName("A minimum refuses the bare number a setting renamed from minutes still carries (JWT_ACCESS_TTL=15: 15 ms)")
    void aMinimumRefusesAStrayBareNumber() {
        assertThatThrownBy(() -> bindValidated(Ttl.class, Map.of(PREFIX + ".access-token-ttl", "15"))).isInstanceOf(BindException.class);
        assertThat(bindValidated(Ttl.class, Map.of(PREFIX + ".access-token-ttl", "15m")).accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("A map keyed by an enum takes one value per constant, its key written in kebab case")
    void aMapKeyedByAnEnumTakesOneValuePerCase() {
        CodeLifetimes codes = bind(CodeLifetimes.class, Map.of(
                PREFIX + ".lifetime.signup-verification", "24h",
                PREFIX + ".lifetime.login-second-factor", "5m"));

        assertThat(codes.lifetime()).containsExactlyInAnyOrderEntriesOf(Map.of(
                CodePurpose.SIGNUP_VERIFICATION, Duration.ofHours(24),
                CodePurpose.LOGIN_SECOND_FACTOR, Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("""
            A URI binds from its text, and an empty value (an unset ${VAR:}) as none; what URI's grammar refuses is \
            encoded instead (a space becomes %20, leaving no host), so the record checks what it relies on""")
    void aUriBindsAndEmptyIsNone() {
        assertThat(bind(Address.class, Map.of(PREFIX + ".endpoint", "https://api.example.com/range/")).endpoint())
                .isEqualTo(URI.create("https://api.example.com/range/"));
        assertThat(binder(Map.of(PREFIX + ".endpoint", "")).bind(PREFIX, Address.class).isBound()).isFalse();
        URI encoded = bind(Address.class, Map.of(PREFIX + ".endpoint", "http://bad host/")).endpoint();
        assertThat(encoded).hasToString("http://bad%20host/");
        assertThat(encoded.isAbsolute()).isTrue();
        assertThat(encoded.getHost()).isNull();
    }

    @Test
    @DisplayName("A mode binds as its enum constant, written in kebab case; a language as a Locale; a content type as a MediaType")
    void modesLanguagesAndContentTypesBind() {
        Formats formats = bind(Formats.class, Map.of(PREFIX + ".mode", "login-second-factor", PREFIX + ".language", "pa-IN",
                PREFIX + ".content-type", "application/problem+json"));

        assertThat(formats.mode()).isEqualTo(CodePurpose.LOGIN_SECOND_FACTOR);
        assertThat(formats.language()).isEqualTo(Locale.forLanguageTag("pa-IN"));
        assertThat(formats.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThatThrownBy(() -> bind(Formats.class, Map.of(PREFIX + ".mode", "second-factor"))).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("A cron binds as a Cron, \"-\" (switched off) included; a malformed one stops the binding")
    void aCronBindsAsACron() {
        assertThat(bind(Schedule.class, Map.of(PREFIX + ".cron", "0 15 3 * * *")).cron()).isEqualTo(Cron.of("0 15 3 * * *"));
        assertThat(bind(Schedule.class, Map.of(PREFIX + ".cron", "-")).cron()).isEqualTo(Cron.of(Cron.DISABLED));
        assertThatThrownBy(() -> bind(Schedule.class, Map.of(PREFIX + ".cron", "0 15 3 * *"))).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("A value with a grammar binds through its public static of(String) alone, and a malformed one stops the binding")
    void aValueBindsThroughItsFactory() {
        assertThat(bind(Share.class, Map.of(PREFIX + ".share", "25%")).share().value).isEqualTo(25);
        assertThatThrownBy(() -> bind(Share.class, Map.of(PREFIX + ".share", "25"))).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("A Duration without a unit Boot knows stops the binding")
    void aMalformedDurationIsRefused() {
        assertThatThrownBy(() -> bind(Spans.class, Map.of(PREFIX + ".lease", "5 minutes"))).isInstanceOf(BindException.class);
    }
}
