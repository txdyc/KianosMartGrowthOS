-- C5 Task 3: job protocol support for video and external (H3) tasks.
alter table generation_job add column external_ref text;
alter table asset add column renditions_json jsonb;
alter table comfy_workflow drop constraint comfy_workflow_code_check;
alter table comfy_workflow add constraint comfy_workflow_code_check check (code in ('CUTOUT','SCENE','H3_I2V'));
