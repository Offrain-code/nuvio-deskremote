@echo off
cd /d "%~dp0"
start "Nuvio DeskRemote Companion" /min node.exe "%~dp0dist\index.js"
