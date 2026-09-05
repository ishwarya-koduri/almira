-- =============================================================================
-- V2 · Catalog: asset taxonomy, institutions, tags, and the universal engine.
-- Refs: docs/01-product-and-scope.md §4-5, docs/04-data-model.md §3 §7
-- =============================================================================

-- -----------------------------------------------------------------------------
-- asset_categories — the 12 top-level buckets from docs/01 §4.
-- `color` is the muted category identifier from docs/02 §2.3 (8px dots only).
-- -----------------------------------------------------------------------------
create table asset_categories (
  id     uuid primary key default gen_random_uuid(),
  code   text not null unique,
  label  text not null,
  icon   text not null,
  color  text not null,
  sort   int  not null
);

-- -----------------------------------------------------------------------------
-- investment_types — smart templates over a generic record.
--
-- `field_schema` is the contract that makes capture type-aware AND
-- server-validated from one source of truth: the client renders the form from
-- it, the API validates `investments.attributes` against it. Shape:
--
--   { "fields": [ { "key": "weight_g", "label": "Weight",
--                   "dataType": "number", "unit": "g",
--                   "required": true, "group": "essential", "sort": 10,
--                   "options": [ {"value":"...","label":"..."} ],   -- select only
--                   "help": "..." } ] }
--
-- group = 'essential' (<=5, always visible) | 'more' (behind "More details").
-- dataType = text | number | money | date | percent | bool | select
--
-- A NULL household_id means a global, built-in type. A non-null household_id is
-- a user-defined custom type, which behaves identically — that is the
-- "record anything" promise (docs/01 §5) and the promotion path is just
-- setting household_id back to NULL: zero migration.
-- -----------------------------------------------------------------------------
create table investment_types (
  id            uuid primary key default gen_random_uuid(),
  category_id   uuid not null references asset_categories(id),
  household_id  uuid references households(id) on delete cascade,
  code          text not null,
  label         text not null,
  icon          text,
  color         text,
  field_schema  jsonb not null default '{"fields":[]}',
  -- Versioned so records captured against an older schema still render
  -- (docs/01 §5 guardrail, docs/07 §1).
  schema_version int not null default 1,
  is_custom     boolean not null default false,
  sort          int not null default 100,
  deleted_at    timestamptz,
  version       int not null default 1,
  created_by    uuid references users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
-- Codes are unique per scope: once globally, once per household.
create unique index investment_types_global_code
  on investment_types (code) where household_id is null;
create unique index investment_types_household_code
  on investment_types (household_id, code) where household_id is not null;
create index on investment_types (category_id);
create trigger investment_types_touch before insert or update on investment_types
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- institutions — banks, AMCs, brokers, insurers, lenders.
-- NULL household_id = a global, seeded institution everyone sees.
-- -----------------------------------------------------------------------------
create table institutions (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid references households(id) on delete cascade,
  name         text not null,
  kind         text not null
                 check (kind in ('bank','amc','broker','insurer','post_office',
                                 'govt','exchange','lender','other')),
  logo_url     text,
  website      text,
  deleted_at   timestamptz,
  version      int not null default 1,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index on institutions (household_id);
create index on institutions (kind);
-- Trigram index powers the searchable, "+ Add institution" combobox (docs/02 §6.3).
create index institutions_name_trgm on institutions using gin (name gin_trgm_ops);
create trigger institutions_touch before insert or update on institutions
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- tags
-- -----------------------------------------------------------------------------
create table tags (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  label        text not null,
  color        text,
  created_at   timestamptz not null default now(),
  unique (household_id, label)
);

-- -----------------------------------------------------------------------------
-- custom_fields — user-defined typed fields, on a whole type or one record.
-- Values live in the record's `attributes` JSONB keyed by `key`.
--
-- GUARDRAIL (docs/01 §5): a custom field with data_type='money' may be counted
-- in value math exactly once — `counts_toward_value` marks the single field that
-- does. A partial unique index makes "exactly once per owner" a database
-- invariant, not a code convention.
-- -----------------------------------------------------------------------------
create table custom_fields (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  owner_type    text not null check (owner_type in ('type','record')),
  owner_id      uuid not null,
  key           text not null,
  label         text not null,
  data_type     text not null
                  check (data_type in ('text','number','money','date','percent','bool','select')),
  unit          text,
  options       jsonb,
  required      boolean not null default false,
  counts_toward_value boolean not null default false,
  sort          int not null default 100,
  created_by    uuid references users(id),
  created_at    timestamptz not null default now(),
  unique (owner_type, owner_id, key),
  constraint custom_field_key_is_identifier check (key ~ '^[a-z][a-z0-9_]{0,48}$'),
  constraint only_money_can_count check (not counts_toward_value or data_type = 'money'),
  constraint select_needs_options check (data_type <> 'select' or options is not null)
);
create unique index custom_fields_one_value_field_per_owner
  on custom_fields (owner_type, owner_id) where counts_toward_value;
create index on custom_fields (household_id);
