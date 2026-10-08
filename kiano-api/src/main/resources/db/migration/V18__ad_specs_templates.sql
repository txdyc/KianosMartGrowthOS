-- C4a: ad asset specs (AD_COPY / AD_STATIC, HERO only) and the template
-- constraint that admits the AD_* overlay + prompt templates.
insert into asset_spec (code, kind, tier, width, height, format, pipeline_ref) values
 ('AD_COPY','TEXT','HERO',null,null,'json','AD_COPY'),
 ('AD_STATIC','IMAGE','HERO',null,null,'jpeg','AD_RENDER');

alter table template drop constraint template_code_check;
alter table template add constraint template_code_check check (code in
  ('PAGE_INFO','PAGE_SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LONG','COPY_SHORT','POLICY_BLOCK',
   'AD_COPY_PROMPT','AD_PRICEHOOK','AD_PROBLEM','AD_DEMO','AD_TRUST'));

alter table template drop constraint template_kind_check;
alter table template add constraint template_kind_check check (kind in
  ('INFOGRAPHIC','SPEC','FACT_PROMPT','COPY_PROMPT','COPY_LAYOUT','POLICY_BLOCK','AD_OVERLAY'));
