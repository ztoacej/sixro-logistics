# Build PostGIS on the native postgres image (including Apple Silicon/arm64).
# PostgreSQL 18 matches the version of the existing Hub SQL dump.
FROM postgres:18-bookworm
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-18-postgis-3 \
    && rm -rf /var/lib/apt/lists/*
