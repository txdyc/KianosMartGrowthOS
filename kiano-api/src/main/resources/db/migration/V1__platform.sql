-- Platform tables: tenant, store, app_user, integration, audit_log, platform_task.
create table tenant (
  id bigint generated always as identity primary key,
  name text not null, slug text not null unique,
  currency char(3) not null default 'GHS', timezone text not null default 'Africa/Accra',
  status text not null default 'ACTIVE', created_at timestamptz not null default now());

insert into tenant (name, slug) values ('KianosMart', 'kianosmart');

create table store (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), platform text not null,
  base_url text not null, status text not null default 'ACTIVE',
  unique (tenant_id, platform));

create table app_user (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  email text not null, name text not null, password_hash text not null,
  role text not null check (role in ('OWNER','OPERATOR','VIEWER')),
  status text not null default 'ACTIVE', created_at timestamptz not null default now(),
  unique (tenant_id, email));           -- email is always stored lower-case

create table integration (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), provider text not null,
  account_ref text not null, credentials_encrypted text not null,
  scopes text, api_version text, status text not null default 'ACTIVE',
  last_sync_at timestamptz, updated_at timestamptz not null default now(),
  unique (tenant_id, provider));

create table audit_log (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  actor_type text not null check (actor_type in ('USER','AI','RULE','SYSTEM')),
  actor_id text, action text not null, target_type text, target_id text,
  before_json jsonb, after_json jsonb, reason text, source text,
  created_at timestamptz not null default now());
create index audit_log_target on audit_log (tenant_id, target_type, target_id, created_at desc);

create table platform_task (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), type text not null,
  payload jsonb not null default '{}', dedupe_key text,
  status text not null check (status in ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  attempts int not null default 0, max_attempts int not null default 3,
  run_after timestamptz not null default now(), locked_by text, locked_until timestamptz,
  result jsonb, last_error text,
  created_at timestamptz not null default now(), started_at timestamptz, finished_at timestamptz);
create unique index platform_task_dedupe on platform_task (tenant_id, type, dedupe_key)
  where dedupe_key is not null and status in ('QUEUED','RUNNING');
create index platform_task_ready on platform_task (run_after) where status = 'QUEUED';
create index platform_task_latest on platform_task (tenant_id, type, id desc);
