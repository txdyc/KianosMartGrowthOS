-- C6 Task 1: batch work runs after interactive work, and batch GPU jobs may be
-- restricted to a night window. Priority 0 = interactive (the previous
-- behavior), higher values are leased later.
alter table generation_job add column priority smallint not null default 0,
  add column batch_id bigint,
  add column night_only boolean not null default false;
drop index generation_job_ready;
create index generation_job_ready on generation_job (executor, priority, id) where status = 'QUEUED';

alter table platform_task add column priority smallint not null default 0;
drop index platform_task_ready;
create index platform_task_ready on platform_task (priority, run_after) where status = 'QUEUED';