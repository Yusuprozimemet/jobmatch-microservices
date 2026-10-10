# The db-setup task (Day 36): scripts/db-setup.py and the one library it needs, and the jobs-seed task's
# seed-mart.py with the two fixtures it loads. The build contexts are scripts/ and the fixtures
# directory (--build-context fixtures=...), so the repository keeps one copy of each and the image holds
# nothing else of it.
FROM python:3.12-slim

# Pinned, as the task runs this image: a new psycopg should arrive by a change here, not by a rebuild.
RUN pip install --no-cache-dir "psycopg[binary]==3.2.13"

WORKDIR /app
COPY db-setup.py /app/db-setup.py
COPY seed-mart.py /app/seed-mart.py
COPY --from=fixtures analytics-schema.sql analytics-seed.sql /app/fixtures/
USER 1000
ENTRYPOINT ["python", "/app/db-setup.py"]
