-- LLM provider routing (spec: 2026-10-08-llm-provider-routing-design §4).
-- Credentials stored encrypted (v1:) via CredentialCipher, AAD = tenantId:llm:{id};
-- keys never appear in APIs, logs or audit. Route pricing per purpose.

create table llm_provider (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  name text not null,
  kind text not null check (kind in ('ANTHROPIC','OPENAI_COMPATIBLE')),
  base_url text,                         -- ANTHROPIC 时为 null
  credentials_encrypted text not null,   -- CredentialCipher，AAD = tenantId:llm:{id}
  status text not null default 'ACTIVE',
  updated_at timestamptz not null default now(),
  unique (tenant_id, name));

create table llm_route (
  tenant_id bigint not null references tenant(id),
  purpose text not null check (purpose in ('FACT_DRAFT','COPY')),
  provider_id bigint not null references llm_provider(id),
  model text not null,
  supports_images boolean not null,
  input_per_mtok numeric(10,4) not null,
  output_per_mtok numeric(10,4) not null,
  cache_read_per_mtok numeric(10,4) not null default 0,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, purpose));

alter table llm_call add column provider text;   -- 例如 'ANTHROPIC'、'OPENAI_COMPATIBLE:DeepSeek'