@echo off
REM compila e executa (requírese maven e java no PATH)
mvn package
IF %ERRORLEVEL% NEQ 0 (
  echo build failed
  pause
  exit /b %ERRORLEVEL%
)
cls
java -jar target\amanuensis-1.0-SNAPSHOT-shaded.jar
pause
