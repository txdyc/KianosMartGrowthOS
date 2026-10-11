-- C5 review fix: decisions made by the system (a video rejected by the AI-ratio
-- gate) are asset_review rows without a human reviewer, so the review board and
-- the reject-reason statistics show why the asset was rejected.
alter table asset_review alter column reviewer drop not null;
