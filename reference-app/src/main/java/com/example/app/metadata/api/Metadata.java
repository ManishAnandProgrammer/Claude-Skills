package com.example.app.metadata.api;

public interface Metadata {

    <T> T get(MetadataKey<T> key);

    <T> void create(MetadataKey<T> key, T value);
    // tenancy-row:begin

    // <T> void createIfAbsent(MetadataKey<T> key, T value);
    // tenancy-row:end

    <T> T createFrom(MetadataKey<T> source, MetadataKey<T> target);

    <T> T update(MetadataKey<T> key, T value, long version);

    void delete(MetadataKey<?> key);
}
