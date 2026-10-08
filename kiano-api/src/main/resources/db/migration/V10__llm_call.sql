-- C3 Task 1: per-call LLM ledger. Every Claude call - success or failure -
-- writes one row, so cost and usage can be audited per tenant (v1.2 §22).

create table llm_call (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  purpose text not null,
  model text not null,
  input_tokens bigint,
  output_tokens bigint,
  cache_read_tokens bigint,
  cache_write_tokens bigint,
  cost_usd numeric(10,6),
  latency_ms int,
  status text not null check (status in ('OK','REFUSED','TRUNCATED','ERROR')),
  stop_reason text,
  error text,
  created_at timestamptz not null default now());
create index llm_call_tenant_time on llm_call (tenant_id, created_at desc);