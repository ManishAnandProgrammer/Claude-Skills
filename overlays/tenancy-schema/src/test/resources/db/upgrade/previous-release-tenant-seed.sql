-- Schema per tenant: one tenant's rows at the previous release, run in its schema (search_path) by
-- support/PreviousReleaseDatabase after the tenant changelog up to that release, which seeded the upload
-- policy and the list defaults and copied its memberships into members. upgrade.tenant names the tenant
-- (Alpha also has a member who left: the members row marked left, their list still there); the heavy owner
-- holds most of Alpha's rows, statuses skewed.

-- The members of its memberships are there already: changeset 0017 copied them in the previous release.
INSERT INTO members (id, user_id, left_at, created_at, updated_at, version)
SELECT gen_random_uuid(), '01890000-0000-7000-8000-000000000003', now() - interval '5 days', now() - interval '300 days', now(), 1
 WHERE current_setting('upgrade.tenant') = '01890000-0000-7000-8100-00000000000a';

INSERT INTO todo_lists (id, owner_id, name, created_at, updated_at, version)
SELECT gen_random_uuid(), member.user_id, 'Upgrade list ' || n, now() - interval '300 days', now(), 0
  FROM members member
 CROSS JOIN LATERAL generate_series(1, case member.user_id when '01890000-0000-7000-8000-000000000001' then 20
                                                          when '01890000-0000-7000-8000-000000000005' then 3 else 1 end) n;
INSERT INTO todos (id, todo_list_id, owner_id, title, status, priority, due_date, completed_at, created_at, updated_at, version)
SELECT uuidv7(), list.id, list.owner_id, 'Upgrade todo ' || n,
       case when n > list.per_list * 0.95 then 'OPEN' else 'DONE' end,
       case n % 10 when 0 then 'HIGH' when 1 then 'MEDIUM' else 'LOW' end,
       case when n % 9 = 0 then null else date '2025-01-01' + n end,
       case when n > list.per_list * 0.95 then null else now() - interval '1 day' end,
       now() - interval '200 days', now(), 0
  FROM (SELECT todo_list.*, case owner_id when '01890000-0000-7000-8000-000000000001' then 60
                                          when '01890000-0000-7000-8000-000000000006' then 30 else 5 end AS per_list
          FROM todo_lists todo_list) list
 CROSS JOIN LATERAL generate_series(1, list.per_list) n;

INSERT INTO tags (id, owner_id, name, active, created_at, updated_at, version)
SELECT gen_random_uuid(), user_id, 'Upgrade tag ' || n, n % 5 <> 0, now(), now(), 0
  FROM members CROSS JOIN generate_series(1, 10) n
 WHERE user_id = '01890000-0000-7000-8000-000000000001';
INSERT INTO todo_tags (id, todo_id, tag_id, owner_id, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, tag.id, todo.owner_id, now(), now(), 0
  FROM todos todo JOIN tags tag ON tag.owner_id = todo.owner_id AND tag.name = 'Upgrade tag ' || (1 + abs(hashtext(todo.id::text)) % 10)
 WHERE abs(hashtext(todo.id::text)) % 5 = 0;
INSERT INTO time_entries (id, todo_id, owner_id, minutes, spent_on, billable, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, todo.owner_id, 15 * n, date '2025-02-01' + n, n % 2 = 0, now(), now(), 0
  FROM (SELECT id, owner_id FROM todos ORDER BY id LIMIT 10) todo CROSS JOIN generate_series(1, 10) n;
INSERT INTO plans (id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, todo.owner_id, date '2025-03-01' + todo.rank, date '2025-03-01' + todo.rank, now(), now(), 0
  FROM (SELECT id, owner_id, (row_number() OVER (PARTITION BY owner_id ORDER BY id))::integer AS rank FROM todos WHERE status = 'OPEN') todo;
-- media:begin

INSERT INTO media_files (id, key, purpose, file_name, content_type, size_bytes, status, uploaded_by, upload_expires_at,
                         expires_at, created_at, updated_at, version)
SELECT gen_random_uuid(), current_setting('upgrade.tenant') || '/TODO_ATTACHMENT/' || gen_random_uuid(), 'TODO_ATTACHMENT',
       'receipt-' || n || '.pdf', 'application/pdf', 1000 + n, 'UPLOADED', '01890000-0000-7000-8000-000000000001',
       now() - interval '30 days', now() - interval '29 days', now() - interval '30 days', now(), 1
  FROM generate_series(1, 6) n
 WHERE current_setting('upgrade.tenant') = '01890000-0000-7000-8100-00000000000a';
INSERT INTO todo_attachments (id, todo_id, media_file_id, created_at, updated_at, version)
SELECT gen_random_uuid(), todo.id, file.id, now(), now(), 0
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS rank FROM todos ORDER BY id LIMIT 6) todo
  JOIN (SELECT id, row_number() OVER (ORDER BY id) AS rank FROM media_files) file ON file.rank = todo.rank;
-- media:end
