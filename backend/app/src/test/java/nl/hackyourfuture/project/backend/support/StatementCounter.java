package nl.hackyourfuture.project.backend.support;

/**
 * Counts the SQL statements the database ran, from {@code pg_stat_statements}.
 *
 * <pre>
 * StatementCounter.reset();
 * anonymous().get("/api/jobs?size=20");
 * assertThat(StatementCounter.statementsMentioning("saved_jobs")).isEqualTo(1);
 * </pre>
 *
 * <p>Watches from the database side, the same side the fixtures work from, so it never wraps or
 * replaces an application bean. It counts executions, not distinct queries: a loop that runs the
 * same query once per row counts once per row.
 *
 * <p>Reset immediately before the request under test. Fixtures run statements too, and so does
 * {@link TestDatabase#reset()}.
 */
public final class StatementCounter {

    private StatementCounter() {
    }

    public static void reset() {
        TestDatabase.jdbc().sql("SELECT public.pg_stat_statements_reset()").query().singleRow();
    }

    /**
     * Executions since the last {@link #reset()} of statements whose text contains {@code fragment},
     * a table name as a rule. Leaves out this class's own queries, which contain it too.
     */
    public static long statementsMentioning(String fragment) {
        return TestDatabase.jdbc().sql("""
                        SELECT COALESCE(SUM(calls), 0)
                        FROM public.pg_stat_statements
                        WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                          AND query ILIKE '%' || :fragment || '%'
                          AND query NOT ILIKE '%pg_stat_statements%'
                        """)
                .param("fragment", fragment)
                .query(Long.class)
                .single();
    }
}
