-- C6 Task 5: review sessions. The two workbenches report their active time
-- (the frontend only counts time since the last input, so idle tabs do not
-- inflate the review-time acceptance metric). Only finished sessions of a SKU
-- that has both a FACT and an ASSETS session feed the average.
create table review_session (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), user_id bigint not null references app_user(id),
  product_id bigint not null references product(id), batch_id bigint references content_batch(id),
  kind text not null check (kind in ('FACT','ASSETS')),
  active_seconds numeric(8,1) not null default 0,
  started_at timestamptz not null default now(), last_heartbeat_at timestamptz not null default now(),
  finished_at timestamptz, outcome text);
create index review_session_product on review_session (tenant_id, product_id, kind);