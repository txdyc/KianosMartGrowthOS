create table category (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), external_id bigint not null,
  parent_external_id bigint, name text not null, slug text not null,
  synced_at timestamptz not null, unique (tenant_id, external_id));

create table product (
  id bigint generated always as identity primary key,
  tenant_id bigint not null references tenant(id), store_id bigint not null references store(id),
  external_id bigint not null, parent_external_id bigint, parent_id bigint references product(id),
  type text not null, sku text, brand text, name text not null, slug text,
  regular_price numeric(12,2), sale_price numeric(12,2), price numeric(12,2),
  stock_qty int, stock_status text, status text not null,
  permalink text, image_url text, woo_modified_at timestamptz, synced_at timestamptz not null,
  unique (tenant_id, external_id));
create index product_sku_ci on product (tenant_id, lower(sku));
create index product_parent on product (parent_id);

create table product_category (
  product_id bigint not null references product(id) on delete cascade,
  category_id bigint not null references category(id) on delete cascade,
  primary key (product_id, category_id));
