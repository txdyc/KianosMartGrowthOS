-- C3 Task 10: per-SKU publications. before_json/after_json hold Woo snapshots
-- for restore/rollback; uploaded_media_ids track what to delete on failure.

create table publication (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  product_id bigint not null references product(id) on delete cascade,
  environment text not null check (environment in ('STAGING','PRODUCTION')),
  target text not null default 'WOO_PRODUCT' check (target in ('WOO_PRODUCT','AD_EXPORT')),
  asset_ids jsonb not null,
  external_ref text,
  before_json jsonb,
  after_json jsonb,
  uploaded_media_ids jsonb not null default '[]',
  archived_asset_ids jsonb not null default '[]',
  status text not null check (status in ('PENDING','APPLIED','ROLLED_BACK','FAILED')),
  needs_attention boolean not null default false,
  error text,
  published_by bigint references app_user(id),
  published_at timestamptz,
  created_at timestamptz not null default now());
create index publication_product_env on publication (tenant_id, product_id, environment, id desc);