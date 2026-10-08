-- C3 Task 5: text copy assets and their specs. COPY_LONG/COPY_SHORT carry
-- rendered HTML; content_json keeps the structured source for re-rendering.

alter table asset add column content_json jsonb;

insert into asset_spec (code, kind, tier, width, height, format, pipeline_ref) values
 ('COPY_TITLE','TEXT','STANDARD',null,null,'text','COPY'),
 ('COPY_SHORT','TEXT','STANDARD',null,null,'html','COPY'),
 ('COPY_LONG','TEXT','STANDARD',null,null,'html','COPY'),
 ('COPY_SEO','TEXT','STANDARD',null,null,'json','COPY'),
 ('COPY_GSHOP','TEXT','STANDARD',null,null,'text','COPY'),
 ('COPY_WA','TEXT','STANDARD',null,null,'text','COPY');