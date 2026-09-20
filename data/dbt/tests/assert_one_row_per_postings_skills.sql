-- The grain of int_postings_skills: one row per posting and skill.
--
-- A singular test is any query that should return no rows. This one returns
-- the pairs that appear more than once, so a failure tells you which skill on
-- which posting broke the rule, not just that something did.
select posting_id, skill, count(*) as rows_found
from {{ ref("int_postings_skills") }}
group by posting_id, skill
having count(*) > 1
