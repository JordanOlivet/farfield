@echo off
rem Starts both dev clients on the test world, each in its own window.
rem Builds once first, so the two Gradle runs do not compete for the same outputs.
cd /d "%~dp0"
call "%~dp0gradlew.bat" :fabric:classes :neoforge:classes
if errorlevel 1 (
    pause
    exit /b 1
)
start "Farfield - Fabric client" cmd /c "%~dp0client-fabric.bat"
start "Farfield - NeoForge client" cmd /c "%~dp0client-neoforge.bat"
