#!/bin/bash
set -e

TLS_ENABLED="${SAYIT_TLS_ENABLED:-true}"
TLS_KEY="${SAYIT_TLS_KEY_FILE:-/app/certs/dev.key}"
TLS_CERT="${SAYIT_TLS_CERT_FILE:-/app/certs/dev.crt}"
HTTP_PORT="${SAYIT_HTTP_PORT:-8000}"
HTTPS_PORT="${SAYIT_HTTPS_PORT:-8443}"

# Bind host: with a token configured we listen on all interfaces (still gated by
# main.py's fail-closed auth). Without a token we bind loopback only, so the LAN
# cannot reach the API at all.
if [ -n "${SAYIT_BIND_HOST}" ]; then
    HTTP_HOST="${SAYIT_BIND_HOST}"
elif [ -n "${SAYIT_API_TOKEN}" ]; then
    HTTP_HOST="0.0.0.0"
else
    HTTP_HOST="127.0.0.1"
fi

if [ "$TLS_ENABLED" = "true" ] && [ -f "$TLS_KEY" ] && [ -f "$TLS_CERT" ]; then
    echo "[entrypoint] Starting HTTPS on :${HTTPS_PORT} (host=${HTTP_HOST})"
    exec python -m uvicorn app.main:app --host "$HTTP_HOST" --port "$HTTPS_PORT" \
        --ssl-keyfile "$TLS_KEY" --ssl-certfile "$TLS_CERT" --no-access-log
else
    echo "[entrypoint] Starting HTTP on :${HTTP_PORT} (host=${HTTP_HOST})"
    exec python -m uvicorn app.main:app --host "$HTTP_HOST" --port "$HTTP_PORT" --no-access-log
fi
