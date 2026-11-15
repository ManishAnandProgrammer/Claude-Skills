-- Schema per tenant: the platform's rows a used database holds at the previous release
-- (support/PreviousReleaseDatabase runs this as the owner, after that release's platform changesets).
-- Tenants in every status; the provisioned ones (ACTIVE, SUSPENDED, CLOSED) recorded in tenant_migrations
-- with no last changeset yet: PreviousReleaseDatabase then creates each one's schema at the previous
-- release and seeds it (previous-release-tenant-seed.sql). The PENDING tenant has no schema (provisioning
-- comes with its approval), the REJECTED one never will (CANCELLED). Members in two tenants, one who left.
-- Its fixed ids are the ones UpgradeFromPreviousReleaseIntegrationTest reads.

INSERT INTO platform.tenants (id, name, status, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8100-00000000000a', 'Upgrade Alpha', 'ACTIVE', now() - interval '400 days', now(), 1),
    ('01890000-0000-7000-8100-00000000000b', 'Upgrade Beta', 'ACTIVE', now() - interval '200 days', now(), 1),
    ('01890000-0000-7000-8100-00000000000c', 'Upgrade Sigma', 'SUSPENDED', now() - interval '300 days', now(), 2),
    ('01890000-0000-7000-8100-00000000000d', 'Upgrade Pi', 'PENDING', now() - interval '2 days', now(), 0),
    ('01890000-0000-7000-8100-00000000000e', 'Upgrade Rho', 'REJECTED', now() - interval '20 days', now(), 1),
    ('01890000-0000-7000-8100-00000000000f', 'Upgrade Chi', 'CLOSED', now() - interval '500 days', now(), 3);
INSERT INTO platform.tenant_status_changes (id, tenant_id, from_status, to_status, source, created_at, updated_at, version)
SELECT gen_random_uuid(), id, null, 'PENDING', 'REGISTRATION', created_at, created_at, 0 FROM platform.tenants WHERE name like 'Upgrade %';
INSERT INTO platform.tenant_migrations (id, tenant_id, schema_name, status, created_at, updated_at, version)
SELECT gen_random_uuid(), id, 't_' || replace(id::text, '-', ''), case status when 'REJECTED' then 'CANCELLED' else 'MIGRATED' end,
       now(), now(), 0
  FROM platform.tenants WHERE name like 'Upgrade %' AND status <> 'PENDING';

INSERT INTO platform.users (id, email, full_name, password_hash, role, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8000-000000000001', 'upgrade-heavy@example.com', 'Heavy Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000002', 'upgrade-light@example.com', 'Light Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000003', 'upgrade-left@example.com', 'Left Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000005', 'upgrade-beta@example.com', 'Beta Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000006', 'upgrade-sigma@example.com', 'Sigma Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000007', 'upgrade-pi@example.com', 'Pi Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000008', 'upgrade-rho@example.com', 'Rho Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000009', 'upgrade-chi@example.com', 'Chi Upgrader', 'not-a-hash', 'USER', now(), now(), 0);
INSERT INTO platform.tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at, version)
SELECT gen_random_uuid(), membership.tenant_id::uuid, membership.user_id::uuid, membership.role, now() - interval '100 days', now(), 0
  FROM (VALUES ('01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000001', 'ADMIN'),
               ('01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000002', 'USER'),
               ('01890000-0000-7000-8100-00000000000b', '01890000-0000-7000-8000-000000000005', 'ADMIN'),
               ('01890000-0000-7000-8100-00000000000b', '01890000-0000-7000-8000-000000000002', 'USER'),
               ('01890000-0000-7000-8100-00000000000c', '01890000-0000-7000-8000-000000000006', 'ADMIN'),
               ('01890000-0000-7000-8100-00000000000d', '01890000-0000-7000-8000-000000000007', 'ADMIN'),
               ('01890000-0000-7000-8100-00000000000e', '01890000-0000-7000-8000-000000000008', 'ADMIN'),
               ('01890000-0000-7000-8100-00000000000f', '01890000-0000-7000-8000-000000000009', 'ADMIN'))
       AS membership (tenant_id, user_id, role);
