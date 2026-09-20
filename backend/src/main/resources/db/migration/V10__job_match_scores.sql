-- Cached model scores for a (user skill set, posting skills) pair. Keyed on skills rather than
-- on the user, so nothing user-identifying is stored and identical profiles share rows.
CREATE TABLE job_match_scores (
    skills_hash    CHAR(64) NOT NULL,
    posting_id     TEXT     NOT NULL,
    scorer_version TEXT     NOT NULL,
    score          INTEGER  NOT NULL,
    reason         TEXT,
    scored_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT job_match_scores_pk PRIMARY KEY (skills_hash, posting_id, scorer_version),
    CONSTRAINT job_match_scores_score_range CHECK (score BETWEEN 0 AND 100)
);

-- Supports the scheduled purge, the only query that does not go through the primary key.
CREATE INDEX idx_job_match_scores_scored_at ON job_match_scores (scored_at);
