-- Saves a Firebase sign-in into the existing public.app_users table.
-- Applied on project khgcajpjouwmddmfkrge.

create or replace function public.upsert_firebase_user(
    p_firebase_uid text,
    p_email text,
    p_name text,
    p_phone text,
    p_provider text,
    p_profile_saved boolean
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    existing public.app_users%rowtype;
    clean_uid text := trim(p_firebase_uid);
    clean_name text := nullif(trim(p_name), '');
    clean_phone text := nullif(trim(p_phone), '');
    clean_email text := nullif(trim(p_email), '');
    clean_provider text := nullif(trim(p_provider), '');
begin
    if clean_uid is null or length(clean_uid) < 6 or length(clean_uid) > 128 then
        raise exception 'invalid firebase uid';
    end if;
    if clean_name is not null and length(clean_name) > 40 then
        raise exception 'invalid name';
    end if;
    if clean_phone is not null and clean_phone !~ '^\+[0-9]{8,15}$' then
        raise exception 'invalid phone';
    end if;

    select * into existing
    from public.app_users
    where firebase_uid = clean_uid;

    if not found then
        insert into public.app_users (
            name, phone, platform, status, firebase_uid, email, sign_in_provider, last_active_at
        ) values (
            coalesce(clean_name, 'Rider'),
            clean_phone,
            'Android',
            case when p_profile_saved and clean_phone is not null then 'Active' else 'Pending' end,
            clean_uid,
            clean_email,
            clean_provider,
            now()
        );
        return;
    end if;

    update public.app_users
    set
        email = coalesce(clean_email, email),
        sign_in_provider = coalesce(clean_provider, sign_in_provider),
        name = case
            when p_profile_saved and clean_name is not null then clean_name
            when name is null or trim(name) = '' or name = 'Rider' then coalesce(clean_name, name)
            else name
        end,
        phone = case
            when p_profile_saved and clean_phone is not null then clean_phone
            when phone is null or trim(phone) = '' then coalesce(clean_phone, phone)
            else phone
        end,
        status = case
            when status = 'Suspended' then status
            when p_profile_saved and clean_phone is not null then 'Active'
            else status
        end,
        last_active_at = now()
    where id = existing.id;
end;
$$;

revoke all on function public.upsert_firebase_user(text, text, text, text, text, boolean) from public, anon, authenticated;
grant execute on function public.upsert_firebase_user(text, text, text, text, text, boolean) to anon, authenticated;
