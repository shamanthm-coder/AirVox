-- Airvox Admin Portal: staff access.
-- Run once in Supabase: SQL Editor > New query > paste > Run.

create table if not exists public.staff (
  user_id    uuid primary key references auth.users (id) on delete cascade,
  role       text not null default 'admin' check (role in ('admin', 'support', 'viewer')),
  created_at timestamptz not null default now()
);

alter table public.staff enable row level security;

-- Signed-in users can see only their own staff row, so the portal can check
-- whether they are staff. Nobody can add or change rows from the portal;
-- staff are added here in the SQL Editor.
drop policy if exists "Staff can read own row" on public.staff;
create policy "Staff can read own row"
  on public.staff for select
  to authenticated
  using (user_id = auth.uid());

-- Add yourself as the first admin. Change the email to the account you
-- created in Authentication > Users, then run this part.
insert into public.staff (user_id, role)
select id, 'admin' from auth.users where email = 'you@example.com'
on conflict (user_id) do update set role = excluded.role;
