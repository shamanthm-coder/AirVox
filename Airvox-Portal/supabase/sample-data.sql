-- Airvox Admin Portal: OPTIONAL sample data, to see the dashboard working
-- before the app saves real data. Run after dashboard.sql.
--
-- Every sample row is marked: sample riders have phone numbers starting
-- '+91 00000', so the "Remove sample data" part at the bottom deletes exactly
-- these rows and nothing real.

begin;

with riders(name, phone, platform, status, wallet, joined_days_ago, active_mins_ago) as (
  values
    ('Manjunath K',   '+91 00000 10482', 'Android', 'Active',    1200, 6,  0),
    ('Akil Saji',     '+91 00000 10481', 'Android', 'Active',     450, 7,  4),
    ('Priya Raman',   '+91 00000 10479', 'iOS',     'Pending',      0, 9,  60),
    ('Rahul Menon',   '+91 00000 10476', 'Android', 'Active',    2300, 12, 12),
    ('Sneha Iyer',    '+91 00000 10470', 'iOS',     'Suspended',  150, 16, 4320),
    ('Vikram Shetty', '+91 00000 10466', 'Android', 'Active',     800, 19, 1440),
    ('Ananya Rao',    '+91 00000 10461', 'Android', 'Active',       0, 23, 120),
    ('Karthik N',     '+91 00000 10455', 'iOS',     'Active',     300, 28, 8640)
)
insert into public.app_users (name, phone, platform, status, wallet_balance, created_at, last_active_at)
select name, phone, platform, status, wallet,
       now() - make_interval(days => joined_days_ago),
       now() - make_interval(mins => active_mins_ago)
from riders;

-- Activity: each active sample rider on most of the last 14 days.
insert into public.user_activity (user_id, day)
select u.id, d::date
from public.app_users u
cross join generate_series(current_date - 13, current_date, interval '1 day') d
where u.phone like '+91 00000%'
  and u.status <> 'Suspended'
  and (extract(day from d)::int + length(u.name)) % 4 <> 0
on conflict do nothing;

insert into public.channels (room, mode, started_by, started_at, ended_at)
select v.room, v.mode, u.id, now() - make_interval(mins => v.started_mins_ago), v.ended
from (values
  ('12345',       'Bluetooth', 'Manjunath K', 42, null::timestamptz),
  ('Nandi Hills', 'Hotspot',   'Akil Saji',   70, null),
  ('City loop',   'Internet',  'Ananya Rao',   8, null),
  ('Old ride',    'Bluetooth', 'Rahul Menon', 600, now() - interval '5 hours')
) v(room, mode, leader, started_mins_ago, ended)
join public.app_users u on u.name = v.leader and u.phone like '+91 00000%';

insert into public.sos_incidents (user_id, location, status, raised_at, resolved_at)
select u.id, v.location, v.status, now() - make_interval(mins => v.mins_ago), v.resolved
from (values
  ('Rahul Menon',   'Nandi Hills Rd, km 14', 'Open',     18,   null::timestamptz),
  ('Vikram Shetty', 'NH 48, Tumakuru',       'Open',     41,   null),
  ('Sneha Iyer',    'Outer Ring Rd',         'Resolved', 1500, now() - interval '20 hours')
) v(rider, location, status, mins_ago, resolved)
join public.app_users u on u.name = v.rider and u.phone like '+91 00000%';

insert into public.wallet_transactions (user_id, kind, amount, status, created_at)
select u.id, v.kind, v.amount, v.status, now() - make_interval(days => v.days_ago)
from (values
  ('Manjunath K',   'topup', 1000, 'succeeded', 1),
  ('Rahul Menon',   'topup', 2000, 'succeeded', 2),
  ('Akil Saji',     'topup',  500, 'succeeded', 3),
  ('Vikram Shetty', 'topup',  800, 'succeeded', 35),
  ('Karthik N',     'topup',  300, 'failed',    1)
) v(rider, kind, amount, status, days_ago)
join public.app_users u on u.name = v.rider and u.phone like '+91 00000%';

insert into public.user_reports (reported_user_id, reason)
select u.id, v.reason
from (values ('Sneha Iyer', 'Abusive language on channel'), ('Karthik N', 'Spam invites')) v(rider, reason)
join public.app_users u on u.name = v.rider and u.phone like '+91 00000%';

insert into public.app_releases (platform, version, rollout_percent)
values ('Android', '1.0.1-sample', 20);

commit;

-- ---------------------------------------------------------------------------
-- Remove sample data (run this part on its own when you no longer need it).
-- Deleting the sample riders also removes their activity and reports;
-- the rest is matched below.
-- ---------------------------------------------------------------------------
-- delete from public.channels where started_by in (select id from public.app_users where phone like '+91 00000%');
-- delete from public.sos_incidents where user_id in (select id from public.app_users where phone like '+91 00000%');
-- delete from public.wallet_transactions where user_id in (select id from public.app_users where phone like '+91 00000%');
-- delete from public.app_releases where version like '%-sample';
-- delete from public.app_users where phone like '+91 00000%';
