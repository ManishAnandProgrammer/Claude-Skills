-- Row mode: the rows a used database holds at the previous release (support/PreviousReleaseDatabase runs
-- this as the owner, after that release's changesets and before the ones that came after it). Tenants in
-- every status, each with what the release gave it: the provisioned ones their seeds (the upload policy,
-- the list defaults), the PENDING and REJECTED ones none (they were never provisioned). Members who are in
-- two tenants, one who left (the membership gone, the tenant's members row marked left, their todos
-- still there). One owner with most of the rows, statuses skewed. The policies are forced on the owner
-- too, so each tenant table's is lifted for its inserts and restored. And what the release's last changesets
-- added: each tenant's todos numbered with its counter after the highest (0026), requesters, one of them the
-- member who left (0025), a suspended account (0027), a deactivated staff member (0028); 0029 to 0032
-- added indexes only. Its fixed ids are
-- the ones UpgradeFromPreviousReleaseIntegrationTest reads.

INSERT INTO tenants (id, name, status, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8100-00000000000a', 'Upgrade Alpha', 'ACTIVE', now() - interval '400 days', now(), 1),
    ('01890000-0000-7000-8100-00000000000b', 'Upgrade Beta', 'ACTIVE', now() - interval '200 days', now(), 1),
    ('01890000-0000-7000-8100-00000000000c', 'Upgrade Sigma', 'SUSPENDED', now() - interval '300 days', now(), 2),
    ('01890000-0000-7000-8100-00000000000d', 'Upgrade Pi', 'PENDING', now() - interval '2 days', now(), 0),
    ('01890000-0000-7000-8100-00000000000e', 'Upgrade Rho', 'REJECTED', now() - interval '20 days', now(), 1),
    ('01890000-0000-7000-8100-00000000000f', 'Upgrade Chi', 'CLOSED', now() - interval '500 days', now(), 3);
INSERT INTO tenant_status_changes (id, tenant_id, from_status, to_status, source, created_at, updated_at, version)
SELECT gen_random_uuid(), id, null, 'PENDING', 'REGISTRATION', created_at, created_at, 0 FROM tenants WHERE name like 'Upgrade %';
INSERT INTO tenant_status_changes (id, tenant_id, from_status, to_status, source, created_at, updated_at, version)
SELECT gen_random_uuid(), id, 'PENDING', 'ACTIVE', 'PROVISIONING', created_at, created_at, 0
  FROM tenants WHERE name like 'Upgrade %' AND status NOT IN ('PENDING', 'REJECTED');

INSERT INTO users (id, email, full_name, password_hash, role, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8000-000000000001', 'upgrade-heavy@example.com', 'Heavy Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000002', 'upgrade-light@example.com', 'Light Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000003', 'upgrade-left@example.com', 'Left Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000005', 'upgrade-beta@example.com', 'Beta Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000006', 'upgrade-sigma@example.com', 'Sigma Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000007', 'upgrade-pi@example.com', 'Pi Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000008', 'upgrade-rho@example.com', 'Rho Upgrader', 'not-a-hash', 'USER', now(), now(), 0),
    ('01890000-0000-7000-8000-000000000009', 'upgrade-chi@example.com', 'Chi Upgrader', 'not-a-hash', 'USER', now(), now(), 0);
-- The REJECTED tenant's owner was suspended (0027).
UPDATE users SET status = 'SUSPENDED' WHERE id = '01890000-0000-7000-8000-000000000008';
-- A staff member, and one deactivated (0028).
INSERT INTO platform_users (id, email, full_name, password_hash, role, deactivated_at, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8200-000000000001', 'upgrade-staff@example.com', 'Staff Upgrader', 'not-a-hash', 'STAFF', null,
     now(), now(), 0),
    ('01890000-0000-7000-8200-000000000002', 'upgrade-gone-staff@example.com', 'Gone Staff', 'not-a-hash', 'STAFF',
     now() - interval '3 days', now() - interval '100 days', now(), 1);
-- (account, tenant, role): the light member is in Alpha and Beta; the left member's membership is gone.
INSERT INTO tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at, version)
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

ALTER TABLE members NO FORCE ROW LEVEL SECURITY;
INSERT INTO members (id, tenant_id, user_id, created_at, updated_at, version)
SELECT gen_random_uuid(), tenant_id, user_id, created_at, created_at, 0 FROM tenant_memberships
 WHERE tenant_id IN (SELECT id FROM tenants WHERE name like 'Upgrade %');
INSERT INTO members (id, tenant_id, user_id, left_at, created_at, updated_at, version) VALUES
    (gen_random_uuid(), '01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000003', now() - interval '5 days',
     now() - interval '300 days', now(), 1);
ALTER TABLE members FORCE ROW LEVEL SECURITY;

-- What the provisioned tenants' seeds gave them at their activation; PENDING and REJECTED never had one.
-- media:begin
ALTER TABLE upload_policies NO FORCE ROW LEVEL SECURITY;
INSERT INTO upload_policies (id, tenant_id, purpose, min_size_bytes, max_size_bytes, min_files, max_files, content_types,
                             created_at, updated_at, version)
SELECT gen_random_uuid(), id, 'TODO_ATTACHMENT', 1, 10485760, 0, 5, '{application/pdf,image/jpeg,image/png}', now(), now(), 0
  FROM tenants WHERE name like 'Upgrade %' AND status NOT IN ('PENDING', 'REJECTED');
ALTER TABLE upload_policies FORCE ROW LEVEL SECURITY;
-- media:end
-- metadata:begin
ALTER TABLE configuration_metadata NO FORCE ROW LEVEL SECURITY;
INSERT INTO configuration_metadata (id, tenant_id, key, value, created_at, updated_at, version)
SELECT gen_random_uuid(), id, 'TODO_LIST_DEFAULTS',
       '{"reminderMinutesBefore": 30, "archiveDoneTodos": false, "dailyDigestAt": "09:00:00"}'::jsonb, now(), now(), 0
  FROM tenants WHERE name like 'Upgrade %' AND status NOT IN ('PENDING', 'REJECTED');
ALTER TABLE configuration_metadata FORCE ROW LEVEL SECURITY;
-- metadata:end

-- (owner, tenant, lists, todos per list): the heavy owner holds most of Alpha; the left member's lists stay.
ALTER TABLE todo_lists NO FORCE ROW LEVEL SECURITY;
ALTER TABLE todos NO FORCE ROW LEVEL SECURITY;
INSERT INTO todo_lists (id, tenant_id, owner_id, name, created_at, updated_at, version)
SELECT gen_random_uuid(), shape.tenant_id::uuid, shape.owner_id::uuid, 'Upgrade list ' || n, now() - interval '300 days', now(), 0
  FROM (VALUES ('01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000001', 20),
               ('01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000002', 2),
               ('01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000003', 1),
               ('01890000-0000-7000-8100-00000000000b', '01890000-0000-7000-8000-000000000005', 3),
               ('01890000-0000-7000-8100-00000000000b', '01890000-0000-7000-8000-000000000002', 1),
               ('01890000-0000-7000-8100-00000000000c', '01890000-0000-7000-8000-000000000006', 1),
               ('01890000-0000-7000-8100-00000000000f', '01890000-0000-7000-8000-000000000009', 1))
       AS shape (tenant_id, owner_id, lists)
 CROSS JOIN generate_series(1, shape.lists) n;
-- Every tenth of the heavy owner's todos (n = 3, 13, ...) asked for by the member who left since.
INSERT INTO todos (id, tenant_id, todo_list_id, owner_id, title, status, priority, due_date, completed_at, created_at, updated_at, version,
-- metadata:begin
                   number,
-- metadata:end
                   requester_id)
SELECT uuidv7(), list.tenant_id, list.id, list.owner_id, 'Upgrade todo ' || n,
       case when n > per_list * 0.95 then 'OPEN' else 'DONE' end,
       case n % 10 when 0 then 'HIGH' when 1 then 'MEDIUM' else 'LOW' end,
       case when n % 9 = 0 then null else date '2025-01-01' + n end,
       case when n > per_list * 0.95 then null else now() - interval '1 day' end,
       now() - interval '200 days', now(), 0,
-- metadata:begin
       row_number() OVER (PARTITION BY list.tenant_id ORDER BY list.id, n),
-- metadata:end
       case when list.owner_id = '01890000-0000-7000-8000-000000000001' AND n % 10 = 3
            then '01890000-0000-7000-8000-000000000003'::uuid end
  FROM (SELECT list.*, case when owner_id = '01890000-0000-7000-8000-000000000001' then 60
                            when owner_id = '01890000-0000-7000-8000-000000000006' then 30 else 5 end AS per_list
          FROM todo_lists list WHERE name like 'Upgrade list %') list
 CROSS JOIN generate_series(1, list.per_list) n;
-- metadata:begin
-- Each tenant's counter after its highest number, whatever its status: 0026's backfill or the seed listener gave
-- every tenant one, and its creates moved it.
ALTER TABLE counters NO FORCE ROW LEVEL SECURITY;
INSERT INTO counters (id, tenant_id, key, last_value, created_at, updated_at, version)
SELECT uuidv7(), t.id, 'TODO_NUMBER', coalesce((SELECT max(todo.number) FROM todos todo WHERE todo.tenant_id = t.id), 0),
       now(), now(), 0
  FROM tenants t WHERE t.name like 'Upgrade %';
ALTER TABLE counters FORCE ROW LEVEL SECURITY;
-- metadata:end
ALTER TABLE todos FORCE ROW LEVEL SECURITY;
ALTER TABLE todo_lists FORCE ROW LEVEL SECURITY;

ALTER TABLE tags NO FORCE ROW LEVEL SECURITY;
ALTER TABLE todo_tags NO FORCE ROW LEVEL SECURITY;
INSERT INTO tags (id, tenant_id, owner_id, name, active, created_at, updated_at, version)
SELECT gen_random_uuid(), '01890000-0000-7000-8100-00000000000a', '01890000-0000-7000-8000-000000000001', 'Upgrade tag ' || n,
       n % 5 <> 0, now(), now(), 0
  FROM generate_series(1, 10) n;
INSERT INTO todo_tags (id, tenant_id, todo_id, tag_id, owner_id, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.tenant_id, todo.id, tag.id, todo.owner_id, now(), now(), 0
  FROM todos todo JOIN tags tag
    ON tag.owner_id = todo.owner_id AND tag.tenant_id = todo.tenant_id AND tag.name = 'Upgrade tag ' || (1 + abs(hashtext(todo.id::text)) % 10)
 WHERE todo.owner_id = '01890000-0000-7000-8000-000000000001' AND abs(hashtext(todo.id::text)) % 5 = 0;
ALTER TABLE todo_tags FORCE ROW LEVEL SECURITY;
ALTER TABLE tags FORCE ROW LEVEL SECURITY;

ALTER TABLE time_entries NO FORCE ROW LEVEL SECURITY;
INSERT INTO time_entries (id, tenant_id, todo_id, owner_id, minutes, spent_on, billable, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.tenant_id, todo.id, todo.owner_id, 15 * n, date '2025-02-01' + n, n % 2 = 0, now(), now(), 0
  FROM (SELECT id, tenant_id, owner_id FROM todos WHERE owner_id = '01890000-0000-7000-8000-000000000001' ORDER BY id LIMIT 10) todo
 CROSS JOIN generate_series(1, 10) n;
ALTER TABLE time_entries FORCE ROW LEVEL SECURITY;

ALTER TABLE plans NO FORCE ROW LEVEL SECURITY;
INSERT INTO plans (id, tenant_id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.tenant_id, todo.id, todo.owner_id, date '2025-03-01' + todo.rank, date '2025-03-01' + todo.rank,
       now(), now(), 0
  FROM (SELECT id, tenant_id, owner_id, (row_number() OVER (ORDER BY id))::integer AS rank
          FROM todos WHERE owner_id = '01890000-0000-7000-8000-000000000001' AND status = 'OPEN') todo;
ALTER TABLE plans FORCE ROW LEVEL SECURITY;
-- media:begin

ALTER TABLE media_files NO FORCE ROW LEVEL SECURITY;
ALTER TABLE todo_attachments NO FORCE ROW LEVEL SECURITY;
INSERT INTO media_files (id, tenant_id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by, upload_expires_at,
                         expires_at, created_at, updated_at, version)
SELECT ('01890000-0000-7000-8003-' || lpad(n::text, 12, '0'))::uuid, '01890000-0000-7000-8100-00000000000a',
       '01890000-0000-7000-8100-00000000000a/TODO_ATTACHMENT/' || gen_random_uuid(), 'TODO_ATTACHMENT', 'receipt-' || n || '.pdf',
       'application/pdf', 1000 + n, 'UPLOADED', '01890000-0000-7000-8000-000000000001',
       now() - interval '30 days', now() - interval '29 days', now() - interval '30 days', now(), 1
  FROM generate_series(1, 6) n;
INSERT INTO todo_attachments (id, tenant_id, todo_id, media_file_id, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.tenant_id, todo.id, ('01890000-0000-7000-8003-' || lpad(todo.rank::text, 12, '0'))::uuid, now(), now(), 0
  FROM (SELECT id, tenant_id, (row_number() OVER (ORDER BY id))::integer AS rank
          FROM todos WHERE owner_id = '01890000-0000-7000-8000-000000000001' ORDER BY id LIMIT 6) todo;
ALTER TABLE todo_attachments FORCE ROW LEVEL SECURITY;
ALTER TABLE media_files FORCE ROW LEVEL SECURITY;
-- media:end
