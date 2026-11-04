-- Credit plans the phone shows. Run once in the Supabase SQL Editor.
-- Safe to run again.

create table if not exists public.credit_plans (
  id         bigint generated always as identity primary key,
  name       text not null,
  credits    integer not null check (credits > 0),
  active     boolean not null default true,
  created_at timestamptz not null default now()
);

alter table public.credit_plans enable row level security;

drop policy if exists "Staff can read" on public.credit_plans;
create policy "Staff can read"
  on public.credit_plans for select to authenticated
  using (public.is_staff());

drop policy if exists "Staff can add plans" on public.credit_plans;
create policy "Staff can add plans"
  on public.credit_plans for insert to authenticated
  with check (public.is_staff());

drop policy if exists "Staff can edit plans" on public.credit_plans;
create policy "Staff can edit plans"
  on public.credit_plans for update to authenticated
  using (public.is_staff())
  with check (public.is_staff());

drop policy if exists "Staff can delete plans" on public.credit_plans;
create policy "Staff can delete plans"
  on public.credit_plans for delete to authenticated
  using (public.is_staff());

grant select, insert, update, delete on public.credit_plans to authenticated;
grant usage, select on sequence public.credit_plans_id_seq to authenticated;

alter table public.credit_plans add column if not exists rupees integer;
update public.credit_plans set rupees = credits where rupees is null;
alter table public.credit_plans alter column rupees set not null;
alter table public.credit_plans drop constraint if exists credit_plans_rupees_check;
alter table public.credit_plans add constraint credit_plans_rupees_check check (rupees > 0);

insert into public.credit_plans (name, credits, rupees)
select name, credits, rupees
from (values
  ('50 credits', 50, 50),
  ('200 credits', 200, 200),
  ('500 credits', 500, 500)
) as seed(name, credits, rupees)
where not exists (select 1 from public.credit_plans);

-- One amount, given once when a rider signs in for the first time. Staff edit it in the portal.
create table if not exists public.credit_settings (
  id integer primary key default 1 check (id = 1),
  welcome_credits integer not null default 20 check (welcome_credits between 0 and 10000)
);

insert into public.credit_settings (id, welcome_credits)
values (1, 20)
on conflict (id) do nothing;

alter table public.credit_settings enable row level security;

drop policy if exists "Staff can read" on public.credit_settings;
create policy "Staff can read"
  on public.credit_settings for select to authenticated
  using (public.is_staff());

drop policy if exists "Staff can edit settings" on public.credit_settings;
create policy "Staff can edit settings"
  on public.credit_settings for update to authenticated
  using (public.is_staff())
  with check (public.is_staff());

grant select, update on public.credit_settings to authenticated;
