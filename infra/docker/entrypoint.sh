#!/bin/sh
set -eu
# Convert a provider connection URI to Spring's JDBC URL. Credentials remain in
# process environment only; they are never written to a config file or command
# arguments. This supports providers such as Neon that issue one connection URI.
if [ -n "${DATABASE_CONNECTION_URI:-}" ] && [ -z "${DATABASE_URL:-}" ]; then
    authority=${DATABASE_CONNECTION_URI#*://}
    export DATABASE_URL="jdbc:postgresql://${authority##*@}"

    if [ "$authority" != "${authority#*@}" ]; then
        credentials=${authority%@*}
        if [ -z "${DATABASE_USER:-}" ]; then
            export DATABASE_USER=${credentials%%:*}
        fi
        if [ -z "${DATABASE_PASSWORD:-}" ]; then
            export DATABASE_PASSWORD=${credentials#*:}
        fi
    fi
fi
exec java -jar /app/app.jar
