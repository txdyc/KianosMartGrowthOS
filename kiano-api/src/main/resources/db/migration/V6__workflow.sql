-- ComfyUI workflow registry: versioned workflow JSON plus its binding
-- manifest per tenant and code (CUTOUT / SCENE). Exactly one APPROVED
-- version per code at a time; older approved versions are RETIRED.

create table comfy_workflow (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  code text not null check (code in ('CUTOUT','SCENE')),
  version int not null,
  workflow_json jsonb not null,
  manifest_json jsonb not null,
  model_refs jsonb not null default '[]',
  status text not null check (status in ('DRAFT','APPROVED','RETIRED')),
  created_by bigint references app_user(id),
  created_at timestamptz not null default now(),
  unique (tenant_id, code, version));
create index comfy_workflow_active on comfy_workflow (tenant_id, code) where status = 'APPROVED';
