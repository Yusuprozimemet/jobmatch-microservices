# The db-setup task (Day 36): scripts/db-setup.py and the one library it needs. The build context
# is scripts/, so the image holds the script and nothing else of the repository.
FROM python:3.12-slim

# Pinned, as the task runs this image: a new psycopg should arrive by a change here, not by a rebuild.
RUN pip install --no-cache-dir "psycopg[binary]==3.2.13"

WORKDIR /app
COPY db-setup.py /app/db-setup.py
USER 1000
ENTRYPOINT ["python", "/app/db-setup.py"]
