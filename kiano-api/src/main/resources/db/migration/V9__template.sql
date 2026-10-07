-- Content template registry: versioned HTML bodies per tenant and code
-- (PAGE_INFO / PAGE_SPEC). Bootstrap ships v1 as APPROVED; published
-- versions are immutable, so a changed body for an existing version fails
-- startup instead of silently altering rendered output.

create table template (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  code text not null check (code in ('PAGE_INFO','PAGE_SPEC')),
  kind text not null check (kind in ('INFOGRAPHIC','SPEC')),
  version int not null,
  body text not null,
  status text not null check (status in ('DRAFT','APPROVED','RETIRED')),
  created_at timestamptz not null default now(),
  unique (tenant_id, code, version));
create index template_approved on template (tenant_id, code) where status = 'APPROVED';
