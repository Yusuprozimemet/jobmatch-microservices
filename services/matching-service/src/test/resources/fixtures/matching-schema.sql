-- What app V10 and V14 leave in production, which the service's db/matching migrations start from.
-- Day 23 deletes the table.

CREATE TABLE matching.job_match_scores (
    skills_hash    CHAR(64) NOT NULL,
    posting_id     TEXT     NOT NULL,
    scorer_version TEXT     NOT NULL,
    score          INTEGER  NOT NULL,
    reason         TEXT,
    scored_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT job_match_scores_pk PRIMARY KEY (skills_hash, posting_id, scorer_version),
    CONSTRAINT job_match_scores_score_range CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX idx_job_match_scores_scored_at ON matching.job_match_scores (scored_at);

ALTER TABLE matching.job_match_scores OWNER TO matching_user;
