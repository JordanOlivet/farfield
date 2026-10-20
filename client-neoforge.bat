@echo off
rem Starts the NeoForge dev client straight into the test world (farfield-test).
rem Run dir: neoforge\run. A stale session.lock left by a killed game is removed first.
cd /d "%~dp0"
if exist "neoforge\run\saves\farfield-test\session.lock" del /q "neoforge\run\saves\farfield-test\session.lock" 2>nul
call "%~dp0gradlew.bat" :neoforge:runClient -PquickPlay=farfield-test %*
if errorlevel 1 pause
