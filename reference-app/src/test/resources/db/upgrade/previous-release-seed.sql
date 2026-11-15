-- The rows a used database holds at the previous release (support/PreviousReleaseDatabase runs this after
-- that release's changesets and before the ones that came after it). Written against the previous
-- release's tables only, and realistic rather than tidy: one owner with most of the rows, statuses
-- skewed (one todo in twenty OPEN, the latest due), every table of the example filled. Its fixed ids are
-- the ones UpgradeFromPreviousReleaseIntegrationTest reads. Raise app.test.upgrade.previous-release (verify.sh)
-- and extend this file when a release ships.

INSERT INTO users (id, email, full_name, password_hash, role, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8000-000000000001', 'upgrade-heavy@example.com', 'Heavy Upgrader', 'not-a-hash', 'USER',
     now() - interval '400 days', now(), 0),
    ('01890000-0000-7000-8000-000000000002', 'upgrade-light@example.com', 'Light Upgrader', 'not-a-hash', 'USER',
     now() - interval '30 days', now(), 0),
    ('01890000-0000-7000-8000-000000000003', 'upgrade-idle@example.com', 'Idle Upgrader', 'not-a-hash', 'USER',
     now() - interval '10 days', now(), 0);
-- admin:begin
INSERT INTO users (id, email, full_name, password_hash, role, created_at, updated_at, version) VALUES
    ('01890000-0000-7000-8000-000000000004', 'upgrade-admin@example.com', 'Admin Upgrader', 'not-a-hash', 'ADMIN',
     now() - interval '400 days', now(), 0);
-- admin:end

INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked_at, revocation_reason, created_at, updated_at, version) VALUES
    (gen_random_uuid(), '01890000-0000-7000-8000-000000000001', 'upgrade-live-token', now() + interval '7 days', null, null,
     now(), now(), 0),
    (gen_random_uuid(), '01890000-0000-7000-8000-000000000001', 'upgrade-rotated-token', now() + interval '6 days', now(), 'ROTATED',
     now() - interval '1 day', now(), 1);

-- The heavy owner: 20 lists of 60 todos (1,200), the light one 3 lists of 5.
INSERT INTO todo_lists (id, owner_id, name, created_at, updated_at, version)
SELECT ('01890000-0000-7000-8001-' || lpad(n::text, 12, '0'))::uuid, '01890000-0000-7000-8000-000000000001',
       'Upgrade list ' || n, now() - interval '300 days', now(), 0
  FROM generate_series(1, 20) n;
INSERT INTO todo_lists (id, owner_id, name, created_at, updated_at, version)
SELECT ('01890000-0000-7000-8002-' || lpad(n::text, 12, '0'))::uuid, '01890000-0000-7000-8000-000000000002',
       'Upgrade list ' || n, now() - interval '20 days', now(), 0
  FROM generate_series(1, 3) n;
INSERT INTO todos (id, todo_list_id, owner_id, title, notes, status, priority, due_date, completed_at, created_at, updated_at, version)
SELECT uuidv7(), list.id, list.owner_id, 'Upgrade todo ' || n, case when n % 7 = 0 then 'Some notes' end,
       case when n > 57 then 'OPEN' else 'DONE' end,
       case n % 10 when 0 then 'HIGH' when 1 then 'MEDIUM' when 2 then 'MEDIUM' else 'LOW' end,
       case when n % 9 = 0 then null else date '2025-01-01' + n end,
       case when n > 57 then null else now() - interval '1 day' end,
       now() - interval '200 days', now(), n % 3
  FROM todo_lists list CROSS JOIN generate_series(1, 60) n
 WHERE list.owner_id = '01890000-0000-7000-8000-000000000001';
INSERT INTO todos (id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
SELECT uuidv7(), list.id, list.owner_id, 'Light todo ' || n, 'OPEN', 'LOW', now(), now(), 0
  FROM todo_lists list CROSS JOIN generate_series(1, 5) n
 WHERE list.owner_id = '01890000-0000-7000-8000-000000000002';

INSERT INTO tags (id, owner_id, name, color, active, created_at, updated_at, version)
SELECT gen_random_uuid(), '01890000-0000-7000-8000-000000000001', 'Upgrade tag ' || n, case when n % 2 = 0 then '#A0B0C0' end,
       n % 5 <> 0, now(), now(), 0
  FROM generate_series(1, 10) n;
INSERT INTO todo_tags (id, todo_id, tag_id, owner_id, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, tag.id, todo.owner_id, now(), now(), 0
  FROM todos todo JOIN tags tag ON tag.owner_id = todo.owner_id AND tag.name = 'Upgrade tag ' || (1 + abs(hashtext(todo.id::text)) % 10)
 WHERE todo.owner_id = '01890000-0000-7000-8000-000000000001' AND abs(hashtext(todo.title)) % 5 = 0;

INSERT INTO time_entries (id, todo_id, owner_id, minutes, spent_on, note, billable, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, todo.owner_id, 15 * n, date '2025-02-01' + n, null, n % 2 = 0, now(), now(), 0
  FROM (SELECT id, owner_id FROM todos WHERE owner_id = '01890000-0000-7000-8000-000000000001' ORDER BY id LIMIT 10) todo
 CROSS JOIN generate_series(1, 10) n;

-- One plan a day for the heavy owner (one owner's plans never share a day).
INSERT INTO plans (id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, todo.owner_id, date '2025-03-01' + todo.rank, date '2025-03-01' + todo.rank, now(), now(), 0
  FROM (SELECT id, owner_id, (row_number() OVER (ORDER BY id))::integer AS rank
          FROM todos WHERE owner_id = '01890000-0000-7000-8000-000000000001' AND status = 'OPEN') todo;
-- metadata:begin

-- Two lists' settings (a per-context key), beside the fixed key 0014 seeded.
INSERT INTO configuration_metadata (id, key, value, created_at, updated_at, version)
SELECT gen_random_uuid(), 'TODO_LIST_SETTINGS_' || id,
       '{"reminderMinutesBefore": 15, "archiveDoneTodos": true, "dailyDigestAt": "08:00:00"}'::jsonb, now(), now(), 2
  FROM todo_lists WHERE owner_id = '01890000-0000-7000-8000-000000000001' ORDER BY id LIMIT 2;
-- metadata:end
-- media:begin

-- Files attached to the heavy owner's first todos (UPLOADED: no job leases them).
INSERT INTO media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by, upload_expires_at,
                         expires_at, created_at, updated_at, version)
SELECT ('01890000-0000-7000-8003-' || lpad(n::text, 12, '0'))::uuid, 'TODO_ATTACHMENT/' || gen_random_uuid(), 'TODO_ATTACHMENT',
       'receipt-' || n || '.pdf', 'application/pdf', 1000 + n, 'UPLOADED', '01890000-0000-7000-8000-000000000001',
       now() - interval '30 days', now() - interval '29 days', now() - interval '30 days', now(), 1
  FROM generate_series(1, 6) n;
INSERT INTO todo_attachments (id, todo_id, media_file_id, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, ('01890000-0000-7000-8003-' || lpad(todo.rank::text, 12, '0'))::uuid, now(), now(), 0
  FROM (SELECT id, (row_number() OVER (ORDER BY id))::integer AS rank
          FROM todos WHERE owner_id = '01890000-0000-7000-8000-000000000001' ORDER BY id LIMIT 6) todo;
-- media:end
