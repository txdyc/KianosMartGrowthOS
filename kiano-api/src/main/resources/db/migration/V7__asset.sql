-- C2 Task 9: asset specs, versioned assets and review decisions.
create table asset_spec (code text primary key, kind text not null, tier text not null,
  width int, height int, format text, pipeline_ref text);
insert into asset_spec values
 ('PAGE_MAIN','IMAGE','STANDARD',1600,1600,'jpeg','WHITE_MAIN'),
 ('PAGE_ANGLE','IMAGE','STANDARD',1600,1600,'jpeg','WHITE_ANGLE'),
 ('PAGE_SCENE','IMAGE','STANDARD',1600,1600,'jpeg','SCENE'),
 ('PAGE_INBOX','IMAGE','STANDARD',1600,1600,'jpeg','INBOX'),
 ('PAGE_INFO','IMAGE','STANDARD',1600,1600,'jpeg','TEMPLATE'),
 ('PAGE_SPEC','IMAGE','STANDARD',1600,1600,'jpeg','TEMPLATE');

create table asset (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id) on delete cascade,
  spec_code text not null references asset_spec(code), variant text not null, version int not null,
  kind text not null check (kind in ('IMAGE','VIDEO','TEXT')),
  object_key text, thumb_object_key text, text_body text, width int, height int, duration_s numeric(8,2),
  status text not null check (status in ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','STALE','ARCHIVED')),
  precheck_json jsonb not null default '{}', provenance_json jsonb not null default '{}',
  ai_ratio numeric(4,3), depends_on_price boolean not null default false, price_snapshot numeric(12,2),
  fact_version int, file_name text, run_id bigint references generation_run(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (tenant_id, product_id, spec_code, variant, version));
create index asset_product_status on asset (tenant_id, product_id, status);

create table asset_review (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), asset_id bigint not null references asset(id) on delete cascade,
  reviewer bigint not null references app_user(id),
  decision text not null check (decision in ('APPROVE','REJECT','REGENERATE')),
  reason_codes jsonb not null default '[]', comment text, created_at timestamptz not null default now());
