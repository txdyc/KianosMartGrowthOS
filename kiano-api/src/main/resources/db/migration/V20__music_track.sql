-- C5 Task 2: built-in licensed music library. Tracks can only be RETIRED,
-- never deleted, because VIDEO_AD provenance references them by id.
create table music_track (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id),
  title text not null, artist text, object_key text not null, duration_s numeric(8,2) not null,
  moods text[] not null default '{}',
  license_type text not null check (license_type in ('CC0','ROYALTY_FREE_COMMERCIAL','OWNED')),
  license_source text not null, license_proof text not null,
  status text not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
  sha256 text not null, created_by bigint references app_user(id), created_at timestamptz not null default now(),
  unique (tenant_id, sha256));
