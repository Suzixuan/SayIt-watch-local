@echo off
setlocal
rem ---------------------------------------------------------------------------
rem One-click SayIt launch with the Watch Receiver.
rem Reads the dev token from a gitignored .watch-dev-token file (already set up),
rem binds 0.0.0.0:18099 so a PC LAN-IP change can't break the Watch link, then
rem starts SayIt. Just double-click this file. No environment setup needed.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
set "EXE=%ROOT%client\src-tauri\target\debug\sayit.exe"
set "TOKEN_FILE=%ROOT%.watch-dev-token"

if not exist "%EXE%" (
  echo [!] SayIt debug binary not found:
  echo     %EXE%
  echo     Build it first:  cd client\src-tauri ^&^& cargo build
  pause
  exit /b 1
)

if not exist "%TOKEN_FILE%" (
  echo [!] Missing token file: %TOKEN_FILE%
  echo     Paste your Watch dev token (one line) into that file, e.g.:
  echo     echo YOUR64HEXTOKEN ^> "%TOKEN_FILE%"
  pause
  exit /b 1
)

set /p TOKEN=<"%TOKEN_FILE%"

rem 0.0.0.0 = listen on every interface (survives PC DHCP IP changes).
set "SAYIT_WATCH_BIND_IP=0.0.0.0"
set "SAYIT_WATCH_PORT=18099"
set "SAYIT_WATCH_DEV_TOKEN=%TOKEN%"

echo Starting SayIt  (Watch Receiver on 0.0.0.0:18099)...
start "" "%EXE%"

endlocal
