package com.example.app.archunit.fixture.settings;

import com.example.app.common.scheduling.Cron;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.time.Period;
import java.util.Map;

@ConfigurationProperties(prefix = "fixture.typed")
public record TypedSettings(Duration lease, Duration timeout, URI apiUrl, Cron cron, int batchSize, Period keepFor,
                            DataSize maxUpload, Map<String, Client> clients) {

    public record Client(Duration readTimeout, URI endpoint) {
    }
}
