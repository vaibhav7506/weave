#!/bin/sh
set -eu
# Render supplies credentials separately. Convert its private URL to JDBC without
# embedding credentials in command arguments, logs, or a generated config file.
if [ -n "${DATABASE_CONNECTION_URI:-}" ] && [ -z "${DATABASE_URL:-}" ]; then
    authority=${DATABASE_CONNECTION_URI#*://}
    export DATABASE_URL="jdbc:postgresql://${authority##*@}"
fi
exec java -jar /app/app.jar
