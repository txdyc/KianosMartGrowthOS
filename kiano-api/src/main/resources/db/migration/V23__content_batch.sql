-- C6 Task 2: content batches. A batch groups STANDARD SKUs so the orchestrator
-- can push them through the pipeline; an item only stores the references the
-- orchestration needs (run, publications), never a derived stage.
create table content_batch (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), name text not null,
  status text not null check (status in ('RUNNING','PAUSED','DONE','CANCELLED')),
  night_only boolean not null default false,
  created_by bigint references app_user(id), created_at timestamptz not null default now(),
  finished_at timestamptz);

create table content_batch_item (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  batch_id bigint not null references content_batch(id), product_id bigint not null references product(id),
  position int not null,
  image_run_id bigint references generation_run(id),
  staging_publication_id bigint references publication(id),
  production_publication_id bigint references publication(id),
  blocked_reason text, blocked_details jsonb,
  added_at timestamptz not null default now(), done_at timestamptz,
  unique (batch_id, product_id));
create index content_batch_item_product on content_batch_item (tenant_id, product_id);

alter table generation_job add constraint generation_job_batch_fk
  foreign key (batch_id) references content_batch(id);