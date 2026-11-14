package com.example.app.metadata.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One configuration: its key and its value as JSON. */
@Entity
@Table(name = "configuration_metadata")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConfigurationMetadata extends AuditableEntity {

    @Column(nullable = false, updatable = false)
    private String key;

    /**
     * A {@code String}: Hibernate passes it through to {@code jsonb} untouched and never calls a JSON
     * format mapper of its own, so the application's {@code JsonMapper} (in {@code MetadataMapper})
     * is the only one that reads and writes the document (DATA-27).
     */
    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String value;
}
