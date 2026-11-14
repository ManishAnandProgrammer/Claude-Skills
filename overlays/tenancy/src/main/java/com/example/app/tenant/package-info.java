/**
 * Tenants: the platform's register of the organisations that use the product (the bootstrap piece
 * {@code tenancy}).
 *
 * <ul>
 *   <li><b>Two worlds:</b> this module is the control plane. Platform staff manage tenants under
 *       {@code /api/v1/platform/**} with platform tokens ({@code aud=platform}, no tenant); every other
 *       endpoint is the tenant plane, reached only with a tenant token. A second security chain keeps
 *       the two apart (house-review {@code references/tenancy.md}).</li>
 *   <li><b>Lifecycle:</b> {@code PENDING -> ACTIVE <-> SUSPENDED -> CLOSED}, and {@code PENDING -> REJECTED}.
 *       Every change is a {@code tenant_status_changes} row naming its source. {@code approve} is the
 *       one way in: staff call it now, a billing module's payment webhook later; provisioning then activates.</li>
 *   <li><b>Status at token issue:</b> login, refresh and switch-tenant ask {@code api.Tenants#requireActive},
 *       so a suspension takes effect within one access-token lifetime.</li>
 *   <li><b>Creating a tenant</b> publishes {@code api.TenantCreated} in the same transaction: the user
 *       module adds the owner's membership. Once the tenant's tables exist,
 *       {@code api.TenantProvisioned} is published under the new tenant: modules seed its defaults.</li>
 *   <!-- tenancy-schema:begin -->
 *   <li><b>Schema per tenant</b> (piece {@code tenancy-schema}): {@code TenantProvisioningJob} creates the
 *       tenant's schema and runs its first migration (the only place that does);
 *       {@code TenantMigrations} brings every schema to the tenant changelog at deploy, one instance at
 *       a time, recording each in {@code tenant_migrations}.</li>
 *   <!-- tenancy-schema:end -->
 *   <!-- tenancy-row:begin -->
 *   <li><b>Row mode</b> (piece {@code tenancy-row}): every tenant shares the tables, so
 *       {@code TenantProvisioning} only activates an approved tenant, right after the approval commits,
 *       seeding its defaults under its scope.</li>
 *   <!-- tenancy-row:end -->
 * </ul>
 *
 * No plans or payment state: a billing module owns those and decides when to call {@code suspend}.
 * The design and its reasons: {@code docs/design/tenancy.md}.
 */
package com.example.app.tenant;
