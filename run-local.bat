@echo off
if "%THORVAULT_TOKEN%"=="" set THORVAULT_TOKEN=change-me
python server\server.py
