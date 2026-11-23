package com.example.app.common.apiversion;

import org.springframework.stereotype.Component;
import org.springframework.web.accept.ApiVersionParser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring's default parser skips any leading non-digits and accepts minor versions, so {@code /api/1/},
 * {@code /api/x1/} and {@code /api/v1.0/} would all serve v1: one resource under many URLs.
 */
@Component
class MajorVersionParser implements ApiVersionParser<ApiVersion> {

    /** No leading zero ({@code v01} would alias {@code v1}); nine digits can't overflow an int. */
    private static final Pattern MAJOR_VERSION = Pattern.compile("v([1-9][0-9]{0,8})");

    @Override
    public ApiVersion parseVersion(String version) {
        Matcher matcher = MAJOR_VERSION.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not an API version: expected v<major>");
        }
        return new ApiVersion(Integer.parseInt(matcher.group(1)));
    }
}
