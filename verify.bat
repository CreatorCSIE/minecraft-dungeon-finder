@echo off
rem Verify dungeon finder core logic (no LWJGL2 needed)
setlocal
cd /d "%~dp0"
if not exist build mkdir build
javac -encoding UTF-8 -d build src\dungeon\core\*.java src\worldgen\*.java src\dungeon\render\TileRenderer.java src\dungeon\Verify.java
if errorlevel 1 goto :eof
java -cp build dungeon.Verify
endlocal