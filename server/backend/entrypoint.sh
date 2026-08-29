#!/bin/bash
set -e

TLS_ENABLED="${SAYIT_TLS_ENABLED:-true}"
TLS_KEY="${SAYIT_TLS_KEY_FILE:-/app/certs/dev.key}"
TLS_CERT="${SAYIT_TLS_CERT_FILE:-/app/certs/dev.crt}"
HTTP_PORT="${SAYIT_HTTP_PORT:-8000}"
HTTPS_PORT="${SAYIT_HTTPS_PORT:-8443}"

if [ "$TLS_ENABLED" = "true" ] && [ -f "$TLS_KEY" ] && [ -f "$TLS_CERT" ]; then
    echo "[entrypoint] Starting HTTPS on :${HTTPS_PORT} (HTTP redirects not needed — clients connect directly)"
    # --no-access-log：访问日志关闭，避免 WebSocket 握手 query 里的鉴权令牌被记录
    exec python -m uvicorn app.main:app --host 0.0.0.0 --port "$HTTPS_PORT" \
        --ssl-keyfile "$TLS_KEY" --ssl-certfile "$TLS_CERT" --no-access-log
else
    echo "[entrypoint] Starting HTTP on :${HTTP_PORT}"
    exec python -m uvicorn app.main:app --host 0.0.0.0 --port "$HTTP_PORT" --no-access-log
fi
