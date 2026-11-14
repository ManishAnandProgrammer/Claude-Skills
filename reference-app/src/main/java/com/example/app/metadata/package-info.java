/**
 * Configuration metadata and counters (the bootstrap piece {@code metadata}).
 *
 * <h2>What belongs here</h2>
 * <ul>
 *   <li><b>Configuration only:</b> one value per key, read often, changed rarely by an admin. Records
 *       that grow, are listed or filtered (forms, templates, documents) are tables of their own:
 *       rows named {@code SURVEY_FORM_<timestamp>} queried with {@code LIKE} and {@code ->>} are the
 *       anti-pattern this piece replaces.</li>
 *   <li><b>One record per key:</b> the value is a plain record ({@code api}), read and written by the
 *       application's {@code JsonMapper}, with its defaults in the compact constructor. Other modules
 *       receive that record. Keys are declared in {@code api.MetadataKeys}, nowhere else.</li>
 *   <li><b>Counters</b> (gapless business numbers) are not metadata: {@code api.Counters}, one atomic
 *       {@code UPDATE ... RETURNING} in the caller's transaction.</li>
 * </ul>
 *
 * <h2>Changing a record</h2>
 * Add a field with a default, deploy it to every instance, and only then let admins set it: an
 * instance still on the old record reads the new field as unknown (tolerated) and drops it when it
 * writes the value back (a copy with {@code createFrom}). A field removed is ignored on read; it stays
 * in stored rows until each is next written.
 *
 * <h2>Seeding</h2>
 * The lifecycle code that creates a context creates its configuration in the same transaction
 * ({@code create}, or {@code createFrom} to start from the previous context). A key added to a
 * running system is seeded for existing contexts by a changeset or a one-off job, never at startup
 * (DATA-28).
 *
 * <h2>History and caching</h2>
 * The audit columns and {@code version} record who changed a value last and when; there is no
 * change history (no Envers). A project that must show earlier values adds history at bootstrap
 * (Envers, an approved dependency, or an append-only {@code configuration_metadata_history} table).
 * No cache: a read is one indexed lookup. A cache is warranted only when a hot path reads the same
 * key several times per request or at a rate the database feels, and then it must be evicted on
 * every write across instances.
 *
 * <h2>Tenancy (chosen at bootstrap)</h2>
 * This reference is single-tenant. The code is the same in all three models; what bootstrap changes:
 * <ul>
 *   <li><b>Schema-per-tenant:</b> nothing in this package and changeset {@code 0008} as it is. Each
 *       tenant schema gets both tables (Liquibase runs per schema when a tenant registers, and for
 *       every schema at deployment); the unique {@code key} is per schema. The tenant comes from the
 *       request (a JWT claim or the host name), and Hibernate's {@code MultiTenantConnectionProvider}
 *       sets {@code search_path} to its schema, so the native counter update needs no change
 *       either.</li>
 *   <li><b>Row-based:</b> changeset {@code 0008} gets {@code tenant_id uuid NOT NULL} in both tables,
 *       and {@code UNIQUE (tenant_id, key)} replaces {@code UNIQUE (key)} (keep the constraint names
 *       {@code MetadataConstraintErrorCodes} maps). Both entities get
 *       {@code @org.hibernate.annotations.TenantId @Column(name = "tenant_id") private UUID tenantId;}:
 *       Hibernate fills it on insert and filters every entity query by it. The tenant id comes from a
 *       {@code CurrentTenantIdentifierResolver} bean reading the access token's tenant claim (one
 *       for the whole application). Native SQL is not filtered: {@code CounterRepository.increment}
 *       adds {@code AND tenant_id = :tenantId}.</li>
 * </ul>
 * Lifecycle code without a request (a scheduled job, tenant registration) opens its session for an
 * explicit tenant in both models.
 */
package com.example.app.metadata;
