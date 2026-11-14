package com.example.app.media.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedContentTypesTest {

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "application/pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    @DisplayName("An allowed type, exactly as listed, is allowed")
    void listedTypesAreAllowed(String contentType) {
        assertThat(AllowedContentTypes.isAllowed(contentType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "text/html", "application/xhtml+xml", "text/xml", "application/xml",
            "image/*", "image/png; charset=utf-8", "IMAGE/PNG", " image/png", "image/png ", "image/pngx",
            "application/pdf+svg", "text/javascript", "application/octet-stream"})
    @DisplayName("SVG, HTML, XML, wildcards, parameters, another case and look-alikes are refused")
    void documentTypesAndLookAlikesAreRefused(String contentType) {
        assertThat(AllowedContentTypes.isAllowed(contentType)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("No type is refused")
    void noTypeIsRefused(String contentType) {
        assertThat(AllowedContentTypes.isAllowed(contentType)).isFalse();
    }
}
