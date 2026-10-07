-- C2 Task 10: scene room rules and Ghanaian-home scene presets.
create table scene_room_rule (keyword text primary key, room text not null);
insert into scene_room_rule values ('blender','KITCHEN'),('kettle','KITCHEN'),('rice-cooker','KITCHEN'),
 ('pressure-cooker','KITCHEN'),('air-fryer','KITCHEN'),('juicer','KITCHEN'),('toaster','KITCHEN'),
 ('stove','KITCHEN'),('fan','LIVING'),('television','LIVING'),('tv','LIVING'),
 ('iron','LAUNDRY'),('steamer','LAUNDRY'),('hair-dryer','BEDROOM');

create table scene_preset (code text primary key, room text not null, sort_order int not null,
  prompt_en text not null);
insert into scene_preset values
 ('KITCHEN-1','KITCHEN',1,'on a clean tiled kitchen counter in a bright Accra apartment, morning light'),
 ('KITCHEN-2','KITCHEN',2,'beside a fruit bowl on a wooden kitchen island, warm afternoon light'),
 ('KITCHEN-3','KITCHEN',3,'on a stovetop with steam rising in the background, cozy family kitchen'),
 ('KITCHEN-4','KITCHEN',4,'on a white countertop next to a window with sheer curtains, soft daylight'),
 ('LIVING-1','LIVING',1,'on a wooden side table in a modest living room, afternoon sun through linen curtains'),
 ('LIVING-2','LIVING',2,'on a tiled floor beside a woven rug and a potted plant, natural light'),
 ('LIVING-3','LIVING',3,'on a TV stand shelf in a family living room, warm evening light'),
 ('LIVING-4','LIVING',4,'on a coffee table with folded newspapers, bright daylight'),
 ('LAUNDRY-1','LAUNDRY',1,'on top of a washing machine in a small utility room, cool daylight'),
 ('LAUNDRY-2','LAUNDRY',2,'on a folding table with neatly stacked towels, clean bright light'),
 ('LAUNDRY-3','LAUNDRY',3,'beside a laundry basket in a tiled laundry corner, soft light'),
 ('LAUNDRY-4','LAUNDRY',4,'on a shelf above a washtub, airy daylight'),
 ('BEDROOM-1','BEDROOM',1,'on a wooden dresser in a bright bedroom, morning light through curtains'),
 ('BEDROOM-2','BEDROOM',2,'on a bedside table next to a lamp and an alarm clock, warm light'),
 ('BEDROOM-3','BEDROOM',3,'on a vanity table with a mirror in the background, soft daylight'),
 ('BEDROOM-4','BEDROOM',4,'on a chest of drawers beside neatly folded clothes, natural light'),
 ('GENERIC-1','GENERIC',1,'on a clean neutral surface in a modern Ghanaian home, even daylight'),
 ('GENERIC-2','GENERIC',2,'on a light wooden table against a plain wall, soft shadow'),
 ('GENERIC-3','GENERIC',3,'in a bright minimal room corner with a potted plant, natural light'),
 ('GENERIC-4','GENERIC',4,'on a marble-look countertop, gentle diffused light');
