-- C3 Task 2: versioned product fact sheets (spec §8). A product has at most
-- one DRAFT and one LOCKED row at a time; the draft is confirmed via the G2
-- lock flow and the previous locked sheet becomes SUPERSEDED.

create table product_fact_sheet (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  product_id bigint not null references product(id) on delete cascade,
  version int not null,
  facts_json jsonb not null,
  field_sources jsonb not null default '{}',
  source_refs jsonb not null default '[]',
  status text not null check (status in ('DRAFT','LOCKED','SUPERSEDED')),
  llm_call_id bigint references llm_call(id),
  locked_by bigint references app_user(id),
  locked_at timestamptz,
  created_by bigint references app_user(id),
  created_at timestamptz not null default now(),
  unique (product_id, version));
create unique index fact_sheet_one_draft on product_fact_sheet (product_id) where status = 'DRAFT';
create unique index fact_sheet_one_locked on product_fact_sheet (product_id) where status = 'LOCKED';