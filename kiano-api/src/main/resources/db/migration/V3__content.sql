create table product_profile (
  product_id bigint primary key references product(id),
  tenant_id bigint not null references tenant(id),
  sku_role text, role_source text not null default 'MANUAL',
  content_tier text not null default 'STANDARD' check (content_tier in ('HERO','STANDARD')),
  product_dna_json jsonb, updated_at timestamptz not null default now());

create table shot_requirement (
  id bigint generated always as identity primary key,
  code text not null, kind text not null check (kind in ('PHOTO','VIDEO')),
  tier text not null check (tier in ('STANDARD','HERO')),
  category text, required boolean not null default true,
  guidance_en text not null, guidance_zh text not null, sort_order int not null);
create unique index shot_requirement_code_cat on shot_requirement (code, coalesce(category, ''));

insert into shot_requirement (code, kind, tier, category, guidance_en, guidance_zh, sort_order) values
 ('P1','PHOTO','STANDARD',null,'Front, eye level','正面，平视',10),
 ('P2','PHOTO','STANDARD',null,'Front-left 45°','左前 45°',20),
 ('P3','PHOTO','STANDARD',null,'Front-right 45°','右前 45°',30),
 ('P4','PHOTO','STANDARD',null,'Side','侧面',40),
 ('P5','PHOTO','STANDARD',null,'Back + rating-plate close-up (used to verify specs)','背面 + 铭牌特写（用于核对参数）',50),
 ('P6','PHOTO','STANDARD',null,'Top, opening or inside','顶部、开口或内部',60),
 ('P7','PHOTO','STANDARD',null,'Control panel / buttons close-up','控制面板、按钮特写',70),
 ('P8','PHOTO','STANDARD',null,'All accessories + packaging box','配件全家福 + 包装盒',80),
 ('P9','PHOTO','HERO',null,'Size reference (in hand, or next to a 1.5L water bottle)','尺寸参照（手持或旁边放 1.5L 水瓶）',90),
 ('V1','VIDEO','HERO',null,'Operation demo, 10–20 s, keep original sound','操作演示 10–20 秒，保留原声',100),
 ('V2','VIDEO','HERO',null,'360° slow orbit','360° 慢速环绕',110),
 ('V3','VIDEO','HERO',null,'Unboxing','开箱',120),
 ('V1','VIDEO','HERO','blender','Pepper, tomato, onion → blended into sauce','辣椒、番茄、洋葱 → 打成酱',101),
 ('V1','VIDEO','HERO','kettle','Fill with water → boil → automatic shut-off','注水 → 烧开 → 自动断电',102),
 ('V1','VIDEO','HERO','rice-cooker','Add rice → start → cooked rice','放米 → 启动 → 煮好的米饭',103),
 ('V1','VIDEO','HERO','cooker','Add rice → start → cooked rice','放米 → 启动 → 煮好的米饭',104),
 ('V1','VIDEO','HERO','iron','Iron a shirt smooth','熨平一件衬衫',105),
 ('V1','VIDEO','HERO','steamer','Iron a shirt smooth','熨平一件衬衫',106),
 ('V1','VIDEO','HERO','air-fryer','Fries or chicken wings, little oil','薯条或鸡翅，少油',107),
 ('V1','VIDEO','HERO','fan','Switch on, change speed, oscillate','开机、调档、摇头',108);

create table source_media (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), product_id bigint not null references product(id),
  shot_code text not null, kind text not null check (kind in ('PHOTO','VIDEO','PROMO_IMAGE')),
  original_file_name text not null, object_key text not null, thumb_object_key text,
  content_type text not null, size_bytes bigint not null,
  width int, height int, duration_s numeric(8,2), sha256 char(64) not null,
  qc_json jsonb not null default '{}',
  status text not null check (status in ('ACCEPTED','RESHOOT','SUPERSEDED')),
  uploaded_by bigint references app_user(id), uploaded_at timestamptz not null default now(),
  unique (tenant_id, sha256));
create index source_media_current on source_media (tenant_id, product_id, shot_code)
  where status <> 'SUPERSEDED';
