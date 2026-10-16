#!/bin/sh
set -eu
# Convert a provider connection URI to Spring's JDBC URL. Credentials remain in
# process environment only; they are never written to a config file or command
# arguments. This supports providers such as Neon that issue one connection URI.
# When it is set, it deliberately takes precedence over older individual
# connection settings left in the hosting environment.
if [ -n "${DATABASE_CONNECTION_URI:-}" ]; then
    authority=${DATABASE_CONNECTION_URI#*://}
    jdbc_url="jdbc:postgresql://${authority##*@}"
    schema=${WEAVE_SCHEMA:-weave}
    case "$jdbc_url" in
        *currentSchema=*) ;;
        *\?*) jdbc_url="${jdbc_url}&currentSchema=${schema}" ;;
        *) jdbc_url="${jdbc_url}?currentSchema=${schema}" ;;
    esac
    export DATABASE_URL="$jdbc_url"

    if [ "$authority" != "${authority#*@}" ]; then
        credentials=${authority%@*}
        export DATABASE_USER=${credentials%%:*}
        export DATABASE_PASSWORD=${credentials#*:}
    fi
fi
exec java -jar /app/app.jar
