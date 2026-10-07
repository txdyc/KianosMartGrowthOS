-- Generation pipeline: one run per pipeline start, jobs leased by workers via
-- FOR UPDATE SKIP LOCKED, and worker liveness for executor availability.

create table generation_run (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  kind text not null default 'IMAGE_SET',
  status text not null check (status in ('RUNNING','DONE','PARTIAL')),
  created_by bigint references app_user(id),
  created_at timestamptz not null default now(), finished_at timestamptz);
create index generation_run_product on generation_run (tenant_id, product_id, id desc);

create table generation_job (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  run_id bigint not null references generation_run(id),
  step text not null, asset_spec_code text, variant text not null, executor text not null,
  input_json jsonb not null default '{}', parent_job_ids bigint[] not null default '{}',
  status text not null check (status in ('QUEUED','LEASED','WAITING_EXECUTOR','SUCCEEDED','FAILED','CANCELLED')),
  attempts int not null default 0, max_attempts int not null default 3,
  run_after timestamptz not null default now(),
  lease_owner text, lease_expires_at timestamptz, heartbeat_at timestamptz,
  output_json jsonb, gpu_seconds numeric(10,2), cost_usd numeric(10,4), error text,
  created_at timestamptz not null default now(), started_at timestamptz, finished_at timestamptz);
create index generation_job_ready on generation_job (executor, run_after) where status = 'QUEUED';
create index generation_job_run on generation_job (run_id);

create table worker_status (
  worker_id text primary key, last_seen_at timestamptz not null,
  capabilities jsonb not null default '[]', unavailable jsonb not null default '[]');
