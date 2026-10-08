-- C4a: promotion dates for the strikethrough-price rule (§7.3). Woo syncs
-- date_on_sale_from_gmt / date_on_sale_to_gmt; sale_to_at changes count as
-- price changes so the price-dependent ad statics re-render after a sale ends.
alter table product add column sale_from_at timestamptz;
alter table product add column sale_to_at timestamptz;
