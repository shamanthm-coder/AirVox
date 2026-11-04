-- Credit transactions. Run once in the Supabase SQL Editor after dashboard.sql.
-- Safe to run again.

create table if not exists public.credit_transactions (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.app_users (id) on delete cascade,
  kind       text not null check (kind in ('consume', 'purchase', 'grant')),
  credits    integer not null check (credits > 0),
  created_at timestamptz not null default now()
);

create index if not exists credit_transactions_created_at_idx on public.credit_transactions (created_at desc);

alter table public.credit_transactions drop constraint if exists credit_transactions_kind_check;
alter table public.credit_transactions add constraint credit_transactions_kind_check
  check (kind in ('consume', 'purchase', 'grant'));

alter table public.credit_transactions enable row level security;
drop policy if exists "Staff can read" on public.credit_transactions;
create policy "Staff can read"
  on public.credit_transactions for select to authenticated
  using (public.is_staff());
