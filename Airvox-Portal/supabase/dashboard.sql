-- Airvox Admin Portal: dashboard tables and the dashboard API.
-- Run once in Supabase, after setup.sql: SQL Editor > New query > paste > Run.
-- Safe to run again; it only adds what is missing and replaces the API function.

-- ---------------------------------------------------------------------------
-- Who is staff (used by every rule below)
-- ---------------------------------------------------------------------------

create or replace function public.is_staff()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.staff where user_id = auth.uid());
$$;

revoke all on function public.is_staff() from public, anon;
grant execute on function public.is_staff() to authenticated;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

-- Riders who use the Airvox app.
create table if not exists public.app_users (
  id             uuid primary key default gen_random_uuid(),
  auth_user_id   uuid unique references auth.users (id) on delete set null,
  name           text not null,
  phone          text,
  platform       text check (platform in ('Android', 'iOS')),
  status         text not null default 'Active' check (status in ('Active', 'Pending', 'Suspended')),
  wallet_balance numeric(12, 2) not null default 0,
  created_at     timestamptz not null default now(),
  last_active_at timestamptz
);

-- One row per rider per day they opened the app; drives "daily active users".
create table if not exists public.user_activity (
  user_id uuid not null references public.app_users (id) on delete cascade,
  day     date not null default current_date,
  primary key (user_id, day)
);

-- Intercom channels. ended_at is empty while the channel is live.
create table if not exists public.channels (
  id         uuid primary key default gen_random_uuid(),
  room       text not null,
  mode       text not null check (mode in ('Bluetooth', 'Hotspot', 'Internet')),
  started_by uuid references public.app_users (id) on delete set null,
  started_at timestamptz not null default now(),
  ended_at   timestamptz
);

create table if not exists public.sos_incidents (
  id          bigint generated always as identity primary key,
  user_id     uuid references public.app_users (id) on delete set null,
  location    text,
  latitude    double precision,
  longitude   double precision,
  status      text not null default 'Open' check (status in ('Open', 'Resolved')),
  raised_at   timestamptz not null default now(),
  resolved_at timestamptz
);

create table if not exists public.wallet_transactions (
  id         bigint generated always as identity primary key,
  user_id    uuid references public.app_users (id) on delete set null,
  kind       text not null check (kind in ('topup', 'payment', 'refund')),
  amount     numeric(12, 2) not null check (amount >= 0),
  status     text not null default 'succeeded' check (status in ('succeeded', 'failed', 'pending')),
  created_at timestamptz not null default now()
);

-- One row each time a rider spends credits on a channel, or adds a credit pack.
-- Credits are points: 1 credit per minute, packs of 50, 200 and 500.
create table if not exists public.credit_transactions (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.app_users (id) on delete cascade,
  kind       text not null check (kind in ('consume', 'purchase', 'grant')),
  credits    integer not null check (credits > 0),
  created_at timestamptz not null default now()
);

create table if not exists public.user_reports (
  id               bigint generated always as identity primary key,
  reported_user_id uuid references public.app_users (id) on delete cascade,
  reason           text not null,
  status           text not null default 'Open' check (status in ('Open', 'Closed')),
  created_at       timestamptz not null default now()
);

create table if not exists public.app_releases (
  id              bigint generated always as identity primary key,
  platform        text not null check (platform in ('Android', 'iOS')),
  version         text not null,
  rollout_percent int not null default 100 check (rollout_percent between 0 and 100),
  released_at     timestamptz not null default now()
);

-- App riders sign in with Firebase; the Airvox server copies them here.
-- (Added after the first version of this file, so existing tables get them too.)
alter table public.app_users add column if not exists firebase_uid text unique;
alter table public.app_users add column if not exists email text;
alter table public.app_users add column if not exists sign_in_provider text;
-- True while the rider is signed in. The Airvana server clears it on sign-out.
alter table public.app_users add column if not exists logged_in boolean not null default false;
alter table public.app_users alter column platform drop not null;
alter table public.app_users alter column status set default 'Active';
create index if not exists app_users_last_active_idx on public.app_users (last_active_at desc);
create index if not exists app_users_logged_in_idx on public.app_users (last_active_at desc) where logged_in;

create index if not exists app_users_created_at_idx on public.app_users (created_at desc);
create index if not exists user_activity_day_idx on public.user_activity (day);
create index if not exists channels_live_idx on public.channels (started_at) where ended_at is null;
create index if not exists sos_incidents_open_idx on public.sos_incidents (raised_at) where status = 'Open';
create index if not exists wallet_transactions_created_at_idx on public.wallet_transactions (created_at);
create index if not exists credit_transactions_created_at_idx on public.credit_transactions (created_at desc);
alter table public.credit_transactions drop constraint if exists credit_transactions_kind_check;
alter table public.credit_transactions add constraint credit_transactions_kind_check
  check (kind in ('consume', 'purchase', 'grant'));

-- Credit packs shown on the phone. Staff create and edit these in the portal.
create table if not exists public.credit_plans (
  id         bigint generated always as identity primary key,
  name       text not null,
  credits    integer not null check (credits > 0),
  rupees     integer not null check (rupees > 0),
  active     boolean not null default true,
  created_at timestamptz not null default now()
);

alter table public.credit_plans add column if not exists rupees integer;
update public.credit_plans set rupees = credits where rupees is null;

-- Free credits given once, on a rider's first sign-in. The portal edits this one row.
create table if not exists public.credit_settings (
  id integer primary key default 1 check (id = 1),
  welcome_credits integer not null default 20 check (welcome_credits between 0 and 10000)
);

insert into public.credit_settings (id, welcome_credits)
values (1, 20)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Security: staff can read everything; nobody else can read anything.
-- The app will get its own write rules when it starts saving to these tables.
-- ---------------------------------------------------------------------------

do $$
declare
  t text;
begin
  foreach t in array array['app_users', 'user_activity', 'channels', 'sos_incidents',
                           'wallet_transactions', 'credit_transactions', 'credit_plans', 'credit_settings', 'user_reports', 'app_releases']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists "Staff can read" on public.%I', t);
    execute format('create policy "Staff can read" on public.%I for select to authenticated using (public.is_staff())', t);
  end loop;
end $$;

-- Staff can approve or suspend a rider. The app server writes with the secret key.
drop policy if exists "Staff can update users" on public.app_users;
create policy "Staff can update users"
  on public.app_users for update to authenticated
  using (public.is_staff())
  with check (public.is_staff());

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

drop policy if exists "Staff can edit settings" on public.credit_settings;
create policy "Staff can edit settings"
  on public.credit_settings for update to authenticated
  using (public.is_staff())
  with check (public.is_staff());

grant select, update on public.credit_settings to authenticated;

-- ---------------------------------------------------------------------------
-- The dashboard API: one call returns everything the Dashboard screen shows.
-- Portal: supabase.rpc('dashboard_summary')
-- ---------------------------------------------------------------------------

create or replace function public.dashboard_summary()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  month_start date := date_trunc('month', current_date)::date;
  result jsonb;
begin
  if not public.is_staff() then
    raise exception 'Only Airvox staff can read the dashboard' using errcode = '42501';
  end if;

  select jsonb_build_object(
    'generatedAt', now(),

    'totalUsers', (select count(*) from public.app_users),
    'newUsers7d', (select count(*) from public.app_users where created_at >= now() - interval '7 days'),
    'activeToday', (select count(*) from public.user_activity where day = current_date),
    -- Signed in, and the app has checked in within the last 5 minutes.
    'loggedIn', (
      select count(*) from public.app_users
      where logged_in and last_active_at >= now() - interval '5 minutes'),
    'onlineNow', (
      select count(*) from public.app_users
      where logged_in and last_active_at >= now() - interval '5 minutes'),

    'liveChannels', (select count(*) from public.channels where ended_at is null),
    'liveOffline', (select count(*) from public.channels where ended_at is null and mode in ('Bluetooth', 'Hotspot')),
    'liveInternet', (select count(*) from public.channels where ended_at is null and mode = 'Internet'),

    'openSos', (select count(*) from public.sos_incidents where status = 'Open'),
    'oldestOpenSosAt', (select min(raised_at) from public.sos_incidents where status = 'Open'),

    'topupsThisMonth', (
      select coalesce(sum(amount), 0) from public.wallet_transactions
      where kind = 'topup' and status = 'succeeded' and created_at >= month_start),
    'topupsLastMonth', (
      select coalesce(sum(amount), 0) from public.wallet_transactions
      where kind = 'topup' and status = 'succeeded'
        and created_at >= (month_start - interval '1 month') and created_at < month_start),

    -- Last 14 days, oldest first, with zero for days nobody was active.
    'dailyActive', (
      select coalesce(jsonb_agg(jsonb_build_object('day', d.day::date, 'count', coalesce(a.n, 0)) order by d.day), '[]'::jsonb)
      from generate_series(current_date - 13, current_date, interval '1 day') as d(day)
      left join (
        select day, count(*) as n from public.user_activity
        where day >= current_date - 13 group by day
      ) a on a.day = d.day::date),

    'openReports', (select count(*) from public.user_reports where status = 'Open'),
    'failedPayments', (
      select count(*) from public.wallet_transactions
      where status = 'failed' and created_at >= now() - interval '7 days'),
    'pendingAccounts', (select count(*) from public.app_users where status = 'Pending'),

    -- Latest release per platform that is still rolling out.
    'rollouts', (
      select coalesce(jsonb_agg(jsonb_build_object('platform', r.platform, 'version', r.version, 'percent', r.rollout_percent)), '[]'::jsonb)
      from (
        select distinct on (platform) platform, version, rollout_percent
        from public.app_releases order by platform, released_at desc
      ) r
      where r.rollout_percent < 100),

    'androidUsers', (select count(*) from public.app_users where platform = 'Android'),
    'iosUsers', (select count(*) from public.app_users where platform = 'iOS'),

    'incidents', (
      select coalesce(jsonb_agg(i order by (i.status = 'Open') desc, i."raisedAt" desc), '[]'::jsonb)
      from (
        select s.id, coalesce(u.name, 'Unknown rider') as rider, s.location,
               s.raised_at as "raisedAt", s.status
        from public.sos_incidents s
        left join public.app_users u on u.id = s.user_id
        order by (s.status = 'Open') desc, s.raised_at desc
        limit 5
      ) i),

    'loggedInUsers', (
      select coalesce(jsonb_agg(n order by n."lastActiveAt" desc), '[]'::jsonb)
      from (
        select id, name, platform, last_active_at as "lastActiveAt"
        from public.app_users
        where logged_in and last_active_at >= now() - interval '5 minutes'
        order by last_active_at desc
        limit 8
      ) n),

    'newestUsers', (
      select coalesce(jsonb_agg(n order by n."joinedAt" desc), '[]'::jsonb)
      from (
        select id, name, platform, status, created_at as "joinedAt"
        from public.app_users order by created_at desc limit 4
      ) n)
  ) into result;

  return result;
end;
$$;

revoke all on function public.dashboard_summary() from public, anon;
grant execute on function public.dashboard_summary() to authenticated;
