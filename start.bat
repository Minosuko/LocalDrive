@echo off
if not exist "storage\chunk" mkdir "storage\chunk"
set TMP=%~dp0storage\chunk
set TEMP=%~dp0storage\chunk

php router.php
pause
