# Postgres + the hypopg extension, for CI only. hypopg isn't in the stock postgres image, and
# GitHub Actions service containers must reference a pre-built image (no local Dockerfile), so
# this is built as an explicit workflow step first and used directly with `docker run`.
# postgresql-<major>-hypopg comes from the PGDG apt repo, which the official postgres image
# already configures — see docker-library/postgres.
FROM postgres:17

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-17-hypopg \
    && rm -rf /var/lib/apt/lists/*
