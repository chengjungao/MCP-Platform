@echo off
REM MCP Bridge Platform - Maven build entry (Windows cmd)
REM Forces project-local .mvn\settings.xml (HTTPS mirror) so that a machine-wide
REM HTTP mirror is not rejected by Maven 3.9's maven-default-http-blocker.
REM Usage: build.cmd clean package
setlocal
where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] mvn not found in PATH. Install Maven 3.9+ first.
  exit /b 1
)
call mvn -s "%~dp0.mvn\settings.xml" -gs "%~dp0.mvn\settings.xml" %*
endlocal
