@echo off
rem Runs simplelogparser from the jar next to this script. Requires Java 21+ on PATH or JAVA_HOME.
setlocal
set "SLP_JAR=%~dp0simplelogparser-1.0-SNAPSHOT.jar"
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java" -jar "%SLP_JAR%" %*
) else (
  java -jar "%SLP_JAR%" %*
)
exit /b %ERRORLEVEL%
