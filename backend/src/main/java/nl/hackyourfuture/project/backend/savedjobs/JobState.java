package nl.hackyourfuture.project.backend.savedjobs;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The job states available for tracking saved jobs")
public enum JobState {
    SAVED,
    APPLIED,
    REJECTED,
    ACCEPTED,
    DECLINED
}