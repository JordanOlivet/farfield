@echo off
rem Starts the Fabric dev client straight into the test world (farfield-test).
rem Run dir: fabric\runs\client. A stale session.lock left by a killed game is removed first.
cd /d "%~dp0"
if exist "fabric\runs\client\saves\farfield-test\session.lock" del /q "fabric\runs\client\saves\farfield-test\session.lock" 2>nul
call "%~dp0gradlew.bat" :fabric:runClient -PquickPlay=farfield-test %*
if errorlevel 1 pause
