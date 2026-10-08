-- C3 Task 4: store policy settings. Every save creates a new version; the
-- latest row is the effective policy. Rendered into COPY_LONG as POLICY_BLOCK.

create table store_policy (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  version int not null,
  sections_json jsonb not null,
  updated_by bigint references app_user(id),
  created_at timestamptz not null default now(),
  unique (tenant_id, version));