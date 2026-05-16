@echo off
set BOOTSTRAP_PORT=%1
if "%BOOTSTRAP_PORT%"=="" set BOOTSTRAP_PORT=9000
set DASHBOARD_PORT=%2
if "%DASHBOARD_PORT%"=="" set DASHBOARD_PORT=9001

docker build -t p2pchat-bootstrap --target bootstrap ..
docker run --rm -it -p %BOOTSTRAP_PORT%:9000 -p %DASHBOARD_PORT%:9001 p2pchat-bootstrap
