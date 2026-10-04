-- A missing skills list and an empty one are the same thing, so the column carries the
-- empty array rather than null and the API can return it without a null check.
-- Split from V6 so the type change and the constraint resting on it stay one concern each.
update user_profiles set skills = '{}' where skills is null;
alter table user_profiles alter column skills set default '{}';
alter table user_profiles alter column skills set not null;
