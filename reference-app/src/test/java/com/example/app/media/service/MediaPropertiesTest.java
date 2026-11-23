package com.example.app.media.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaPropertiesTest {

    private static MediaProperties bind(Map<String, String> values) {
        Binder binder = new Binder(List.of(new MapConfigurationPropertySource(values)), null,
                ApplicationConversionService.getSharedInstance());
        return binder.bind("app.media", MediaProperties.class).get();
    }

    @Test
    @DisplayName("The region binds as the SDK's Region; an empty endpoint (MEDIA_ENDPOINT unset) is AWS's, none")
    void regionAndEndpointBind() {
        MediaProperties aws = bind(Map.of("app.media.bucket", "media", "app.media.region", "eu-west-1", "app.media.endpoint", ""));
        MediaProperties store = bind(Map.of("app.media.region", "us-east-1", "app.media.endpoint", "http://localhost:7070"));

        assertThat(aws.region()).isEqualTo(Region.EU_WEST_1);
        assertThat(aws.endpoint()).isNull();
        assertThat(store.endpoint()).isEqualTo(URI.create("http://localhost:7070"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"upload-url-expiry, 999ms", "upload-url-expiry, 8d", "read-url-expiry, 999ms", "read-url-expiry, 8d"})
    @DisplayName("A URL expiry outside SigV4's 1 second to 7 days stops startup: the SDK would refuse every presign")
    void urlExpiriesStayWithinWhatSigV4Signs(String key, String expiry) {
        assertThatThrownBy(() -> bind(Map.of("app.media.region", "us-east-1", "app.media." + key, expiry)))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.media." + key + " must be between 1s and 7d (SigV4)");
        assertThat(bind(Map.of("app.media.region", "us-east-1", "app.media." + key, "7d"))).isNotNull();
    }

    @Test
    @DisplayName("A relative endpoint stops startup: the SDK would resolve every request against nothing")
    void aRelativeEndpointIsRefused() {
        assertThatThrownBy(() -> bind(Map.of("app.media.region", "us-east-1", "app.media.endpoint", "//localhost:7070")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.media.endpoint must be empty (AWS) or an absolute URL with a host");
    }
}
