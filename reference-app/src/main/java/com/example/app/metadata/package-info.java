/**
 * Configuration metadata and counters (the bootstrap piece {@code metadata}).
 *
 * <ul>
 *   <li><b>Configuration only:</b> one value per key, read often, changed rarely. Records that grow,
 *       are listed or filtered are tables of their own.</li>
 *   <li><b>One record per key</b> ({@code api}), its defaults in the compact constructor; keys are
 *       declared in {@code api.MetadataKeys}, nowhere else.</li>
 *   <li><b>A fixed key</b> is changed by an admin through {@code GET/PUT /metadata/{key}}; <b>a
 *       per-context key</b> only by the module that owns the context, through a typed endpoint of its
 *       own resource that checks its rules first ({@code PUT /stores/{id}/opening-hours}).</li>
 *   <li><b>Counters</b> (gapless business numbers) are not metadata: {@code api.Counters}, one atomic
 *       update in the caller's transaction.</li>
 *   <li>The code that creates or deletes a context creates or deletes its values and counters in the
 *       same transaction. Single-tenant here.</li>
 * </ul>
 *
 * The rules with their reasons (adding a field, seeding, tenancy, history, caching): house-review
 * {@code references/metadata.md} (META-01…09).
 */
package com.example.app.metadata;
