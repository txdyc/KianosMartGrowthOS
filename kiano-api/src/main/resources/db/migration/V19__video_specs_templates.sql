-- C5 Task 1: video asset specs (VIDEO_SCRIPT / VIDEO_AD) and the template
-- constraint that admits the VIDEO_* + H3_* prompt/overlay templates.
insert into asset_spec (code, kind, tier, width, height, format, pipeline_ref) values
 ('VIDEO_SCRIPT','TEXT','HERO',null,null,'json','VIDEO_SCRIPT'),
 ('VIDEO_AD','VIDEO','HERO',1080,1920,'mp4','VIDEO');

alter table template drop constraint template_code_check;
alter table template add constraint template_code_check check (code in
  ('PAGE_INFO','PAGE_SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LONG','COPY_SHORT','POLICY_BLOCK',
   'AD_COPY_PROMPT','AD_PRICEHOOK','AD_PROBLEM','AD_DEMO','AD_TRUST',
   'VIDEO_SCRIPT_PROMPT','VIDEO_CAPTION','END_CARD','H3_PROMPT_DEMO','H3_PROMPT_PROBLEM','H3_PROMPT_UNBOXING'));

alter table template drop constraint template_kind_check;
alter table template add constraint template_kind_check check (kind in
  ('INFOGRAPHIC','SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LAYOUT','POLICY_BLOCK','AD_OVERLAY',
   'VIDEO_SCRIPT_PROMPT','VIDEO_OVERLAY','END_CARD','H3_PROMPT'));
