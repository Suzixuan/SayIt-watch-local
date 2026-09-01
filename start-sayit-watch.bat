@echo off
setlocal
rem ---------------------------------------------------------------------------
rem Start SayIt with the Watch Receiver so the Galaxy Watch can reach it.
rem Fixes the "no reaction after a few recordings" issue: the Receiver only
rem starts when these env vars are present, and binding 0.0.0.0 means a PC
rem LAN-IP change no longer breaks the Watch link.
rem ---------------------------------------------------------------------------

set "ROOT=%~dp0"
set "EXE=%ROOT%client\src-tauri\target\debug\sayit.exe"
set "TOKEN_FILE=%ROOT%.watch-dev-token"

if not exist "%EXE%" (
  echo [!] SayIt debug binary not found:
  echo     %EXE%
  echo     Build it first: cd client\src-tauri ^&^& cargo build
  pause
  exit /b 1
)

if not exist "%TOKEN_FILE%" (
  echo [!] Missing token file:
  echo     %TOKEN_FILE%
  echo     Put your 64-hex dev token there (one line). Example:
  echo     echo YOUR64HEXTOKEN ^> "%TOKEN_FILE%"
  pause
  exit /b 1
)

set /p TOKEN=<"%TOKEN_FILE%"

rem 0.0.0.0 = listen on every interface (survives DHCP IP changes).
set "SAYIT_WATCH_BIND_IP=0.0.0.0"
set "SAYIT_WATCH_PORT=18099"
set "SAYIT_WATCH_DEV_TOKEN=%TOKEN%"

echo Starting SayIt  (Watch Receiver on 0.0.0.0:18099)...
start "" "%EXE%"

endlocal
