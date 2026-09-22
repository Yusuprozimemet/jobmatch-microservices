-- The baseline mart every test starts from: 24 postings across real Dutch cities,
-- five categories, three work modes and an age range from today to four months old.
--
-- Re-seeded before each test, so a test may insert its own postings with aPosting()
-- and never has to clean up after itself.
--
-- Deliberate edge cases, because they are the ones that break mart queries:
--   seed-0009  a repost of seed-0001 - same title and company, different id
--   seed-0013  two cities on one posting
--   seed-0021  a "netherlands" value in the city bridge, which is a country, not a city
--   seed-0023  an empty skills array
--   seed-0024  closed, with closed_at set
--
-- The varying columns are listed once in the CTE; everything else is derived from them,
-- the same way the dbt marts derive it, so the seed cannot drift into a shape the
-- pipeline would never produce.

WITH seed (
    posting_id, title, company_name, cities, work_mode, employment_type,
    category, experience_level, education_level, skills, salary_min, salary_max,
    age_days, status
) AS (
    VALUES
    ('seed-0001', 'Backend Developer', 'Nedap', '["amsterdam"]', 'onsite', 'full_time',
     'software_engineering', 'medior', 'bachelor', '["java","spring","sql","postgresql"]', 4500, 6000, 1, 'open'),
    ('seed-0002', 'Frontend Developer', 'Adyen', '["amsterdam"]', 'hybrid', 'full_time',
     'software_engineering', 'junior', 'bachelor', '["css","javascript","react","typescript"]', 3500, 4800, 2, 'open'),
    ('seed-0003', 'Data Engineer', 'Booking.com', '["amsterdam"]', 'hybrid', 'full_time',
     'data_engineering', 'medior', 'master', '["airflow","dbt","python","sql"]', 5000, 6500, 3, 'open'),
    ('seed-0004', 'Data Analyst', 'ING', '["rotterdam"]', 'onsite', 'full_time',
     'data_analytics', 'junior', 'bachelor', '["excel","power bi","sql"]', 3200, 4200, 5, 'open'),
    ('seed-0005', 'Senior Data Engineer', 'ASML', '["eindhoven"]', 'onsite', 'full_time',
     'data_engineering', 'senior', 'master', '["databricks","python","spark","sql"]', 6500, 8500, 6, 'open'),
    ('seed-0006', 'DevOps Engineer', 'Philips', '["eindhoven"]', 'hybrid', 'full_time',
     'devops', 'medior', 'bachelor', '["aws","docker","kubernetes","terraform"]', 5000, 6800, 7, 'open'),
    ('seed-0007', 'Full Stack Developer', 'Coolblue', '["rotterdam"]', 'hybrid', 'full_time',
     'software_engineering', 'medior', 'bachelor', '["java","react","sql"]', 4200, 5600, 8, 'open'),
    ('seed-0008', 'Machine Learning Engineer', 'Ahold Delhaize', '["utrecht"]', 'remote', 'full_time',
     'data_science', 'senior', 'master', '["mlflow","python","pytorch","sql"]', 6000, 8000, 10, 'open'),
    ('seed-0009', 'Backend Developer', 'Nedap', '["amsterdam"]', 'onsite', 'full_time',
     'software_engineering', 'medior', 'bachelor', '["java","spring","sql"]', 4500, 6000, 12, 'open'),
    ('seed-0010', 'Product Designer', 'Mollie', '["amsterdam"]', 'hybrid', 'full_time',
     'design', 'medior', 'bachelor', '["figma","prototyping","ux research"]', 4000, 5500, 14, 'open'),
    ('seed-0011', 'Junior Java Developer', 'Rabobank', '["utrecht"]', 'onsite', 'full_time',
     'software_engineering', 'junior', 'bachelor', '["git","java","sql"]', 3000, 4000, 15, 'open'),
    ('seed-0012', 'Cloud Engineer', 'KPN', '["den haag"]', 'hybrid', 'full_time',
     'devops', 'medior', 'bachelor', '["azure","kubernetes","terraform"]', 4800, 6400, 18, 'open'),
    ('seed-0013', 'Python Developer', 'TomTom', '["amsterdam","eindhoven"]', 'hybrid', 'full_time',
     'software_engineering', 'medior', 'bachelor', '["fastapi","postgresql","python"]', 4600, 6200, 20, 'open'),
    ('seed-0014', 'Analytics Engineer', 'bol.com', '["utrecht"]', 'remote', 'full_time',
     'data_engineering', 'medior', 'bachelor', '["dbt","python","snowflake","sql"]', 4800, 6300, 22, 'open'),
    ('seed-0015', 'QA Engineer', 'Picnic', '["amsterdam"]', 'onsite', 'full_time',
     'software_engineering', 'junior', 'bachelor', '["java","selenium","testing"]', 3400, 4500, 25, 'open'),
    ('seed-0016', 'Site Reliability Engineer', 'Adyen', '["amsterdam"]', 'hybrid', 'full_time',
     'devops', 'senior', 'master', '["go","kubernetes","linux","prometheus"]', 6200, 8200, 30, 'open'),
    ('seed-0017', 'Data Scientist', 'Shell', '["den haag"]', 'hybrid', 'contract',
     'data_science', 'senior', 'phd', '["python","r","sql","statistics"]', 7000, 9000, 35, 'open'),
    ('seed-0018', 'Frontend Engineer', 'Backbase', '["amsterdam"]', 'remote', 'part_time',
     'software_engineering', 'junior', 'bachelor', '["css","javascript","vue"]', 2400, 3200, 40, 'open'),
    ('seed-0019', 'Database Administrator', 'Alliander', '["arnhem"]', 'onsite', 'full_time',
     'devops', 'senior', 'bachelor', '["linux","postgresql","sql"]', 5200, 6800, 45, 'open'),
    ('seed-0020', 'Software Engineer', 'Thales', '["delft"]', 'onsite', 'full_time',
     'software_engineering', 'medior', 'master', '["c++","cmake","linux"]', 4400, 6000, 60, 'open'),
    ('seed-0021', 'Business Intelligence Developer', 'NS', '["utrecht","netherlands"]', 'hybrid', 'full_time',
     'data_analytics', 'medior', 'bachelor', '["python","sql","tableau"]', 4300, 5700, 75, 'open'),
    ('seed-0022', 'Platform Engineer', 'Groningen Digital', '["groningen"]', 'remote', 'contract',
     'devops', 'senior', 'bachelor', '["aws","kubernetes","python","terraform"]', 6000, 7800, 90, 'open'),
    ('seed-0023', 'Graduate Software Engineer', 'Sioux', '["eindhoven"]', 'onsite', 'full_time',
     'software_engineering', 'junior', 'bachelor', '[]', 2800, 3400, 11, 'open'),
    ('seed-0024', 'Legacy Systems Engineer', 'Ordina', '["amsterdam"]', 'onsite', 'full_time',
     'software_engineering', 'senior', 'bachelor', '["cobol","db2"]', 5000, 6500, 120, 'closed')
)
INSERT INTO analytics.fct_postings (
    posting_id, source, source_job_id, title, company_name, location, countries, regions,
    cities, has_location_data, work_mode, is_remote, skills, skill_count, experience_level,
    education_level, employment_type, salary_min, salary_max, salary_currency, salary_period,
    category, description, posted_at, posted_date, updated_at, last_seen_at, closed_at,
    status, freshness_class, age_days, repost_count, fake_freshness, source_url,
    ingest_date, ingested_at
)
SELECT
    posting_id,
    'seed',
    'seed-source-' || posting_id,
    title,
    company_name,
    (SELECT string_agg(initcap(c), ', ' ORDER BY c) FROM jsonb_array_elements_text(cities::jsonb) c),
    '["netherlands"]',
    '[]',
    cities,
    true,
    work_mode,
    work_mode = 'remote',
    skills,
    jsonb_array_length(skills::jsonb),
    experience_level,
    education_level,
    employment_type,
    salary_min,
    salary_max,
    'EUR',
    'month',
    category,
    'Seeded posting for integration tests: ' || title || ' at ' || company_name || '.',
    now() - make_interval(days => age_days),
    current_date - age_days,
    now() - make_interval(days => age_days),
    now(),
    CASE WHEN status = 'closed' THEN now() - make_interval(days => 1) END,
    status,
    CASE WHEN age_days <= 7 THEN 'fresh' WHEN age_days <= 30 THEN 'recent' ELSE 'stale' END,
    age_days,
    0,
    false,
    'https://example.test/jobs/' || posting_id,
    current_date,
    now()
FROM seed;

-- The two bridge tables are exploded from the fact table, exactly as fct_postings_cities
-- and fct_postings_skills are built from int_postings upstream.
INSERT INTO analytics.fct_postings_cities (posting_id, city, source, title, posted_at, posted_date)
SELECT p.posting_id, c, p.source, p.title, p.posted_at, p.posted_date
FROM analytics.fct_postings p, jsonb_array_elements_text(p.cities::jsonb) c;

INSERT INTO analytics.fct_postings_skills (posting_id, skill, source, title, posted_at, posted_date)
SELECT p.posting_id, s, p.source, p.title, p.posted_at, p.posted_date
FROM analytics.fct_postings p, jsonb_array_elements_text(p.skills::jsonb) s;
