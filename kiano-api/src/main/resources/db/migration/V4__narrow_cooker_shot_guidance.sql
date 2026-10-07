-- V1 guidance now also matches on product names (not only category slugs).
-- The bare 'cooker' keyword then hits infrared/induction cooktops and egg
-- cookers, which must not get the rice demo script. Spec §6 only defines the
-- script for rice cookers and electric cooking pots, so narrow it to those.
delete from shot_requirement where code = 'V1' and category = 'cooker';

insert into shot_requirement (code, kind, tier, category, guidance_en, guidance_zh, sort_order) values
 ('V1','VIDEO','HERO','pressure-cooker','Add rice → start → cooked rice','放米 → 启动 → 煮好的米饭',104);
