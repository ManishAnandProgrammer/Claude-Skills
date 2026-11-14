package com.example.app.metadata.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.metadata.api.Metadata;
import com.example.app.metadata.api.MetadataKey;
import com.example.app.metadata.api.MetadataKeys;
import com.example.app.metadata.domain.ConfigurationMetadata;
import com.example.app.metadata.dto.request.MetadataUpdateRequest;
import com.example.app.metadata.dto.response.MetadataResponse;
import com.example.app.metadata.mapper.MetadataMapper;
import com.example.app.metadata.repository.ConfigurationMetadataRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration metadata: the typed methods of {@link Metadata} for other modules, and the admin
 * API's by-name methods, which reach only keys declared in {@link MetadataKeys}.
 *
 * <p>Every read is one primary-key-like lookup ({@code uq_configuration_metadata_key}), so there is
 * no cache.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetadataService implements Metadata {

    private static final String VALUE = "value.";

    private final ConfigurationMetadataRepository repository;
    private final MetadataMapper mapper;
    private final Validator validator;

    /**
     * 404 when absent, never the record's defaults: a missing row means its seeding never ran, and a
     * silent default would hide that, show admins a value that isn't stored, and leave nothing to update.
     */
    @Override
    public <T> T get(MetadataKey<T> key) {
        return mapper.toValue(getMetadata(key), key);
    }

    /** A key that exists is refused at the flush by {@code uq_configuration_metadata_key}: 409 {@code METADATA_KEY_TAKEN}. */
    @Override
    @Transactional
    public <T> void create(MetadataKey<T> key, T value) {
        validate(value);
        repository.saveAndFlush(mapper.toEntity(key, value));
    }

    /** Copies the value as stored, without validating it again: it was valid when written, and a limit lowered since must not block the new context. */
    @Override
    @Transactional
    public <T> T createFrom(MetadataKey<T> source, MetadataKey<T> target) {
        T value = get(source);
        repository.saveAndFlush(mapper.toEntity(target, value));
        return value;
    }

    @Override
    @Transactional
    public <T> T update(MetadataKey<T> key, T value, long version) {
        validate(value);
        replace(key, value, version);
        return value;
    }

    /** 404 {@code METADATA_KEY_UNKNOWN} for a name {@link MetadataKeys} doesn't declare. */
    public ObjectWrapper<MetadataResponse> findByName(String name) {
        MetadataKey<?> key = declaredKey(name);
        ConfigurationMetadata metadata = getMetadata(key);
        return ObjectWrapper.withObject(mapper.toResponse(metadata, mapper.toValue(metadata, key)));
    }

    @Transactional
    public ObjectWrapper<MetadataResponse> updateByName(String name, MetadataUpdateRequest request) {
        return ObjectWrapper.withObject(updateFromRequest(declaredKey(name), request));
    }

    private <T> MetadataResponse updateFromRequest(MetadataKey<T> key, MetadataUpdateRequest request) {
        T value = mapper.toValue(request.value(), key);
        validate(value);
        return mapper.toResponse(replace(key, value, request.version()), value);
    }

    /**
     * Compares records, not text: the stored text is {@code jsonb}'s normal form and never equals a
     * fresh write byte for byte, and an unchanged value must not bump the version.
     */
    private <T> ConfigurationMetadata replace(MetadataKey<T> key, T value, long version) {
        ConfigurationMetadata metadata = getMetadata(key);
        metadata.checkVersion(version);
        if (!mapper.toValue(metadata, key).equals(value)) {
            metadata.setValue(mapper.toJson(value));
            repository.flush();
        }
        return metadata;
    }

    private <T> void validate(T value) {
        Objects.requireNonNull(value, "value");
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            List<String> failed = violations.stream().map(MetadataService::describe).sorted().toList();
            throw AppException.withFailedValidations(ErrorCode.VALIDATION_FAILED, failed);
        }
    }

    private static String describe(ConstraintViolation<?> violation) {
        return VALUE + violation.getPropertyPath() + ": " + violation.getMessage();
    }

    private static MetadataKey<?> declaredKey(String name) {
        return MetadataKeys.byName(name).orElseThrow(AppException.supply(ErrorCode.METADATA_KEY_UNKNOWN));
    }

    private ConfigurationMetadata getMetadata(MetadataKey<?> key) {
        return repository.findByKey(key.name()).orElseThrow(AppException.supply(ErrorCode.METADATA_NOT_FOUND, key.name()));
    }
}
