@echo off
rem ============================================================
rem  Dungeon Finder - compile source and package into a jar
rem  Output: dungeon-finder.jar (root)
rem ============================================================
setlocal
cd /d "%~dp0"

rem clean previous build
if exist build rmdir /s /q build
mkdir build

rem extract native libraries on first build
if not exist natives (
  echo Extracting native libraries to natives\ ...
  mkdir natives
  tar -xf lib\windows_natives.jar -C natives
)

echo Compiling...
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d build -cp "lib\lwjgl.jar;lib\lwjgl_util.jar;lib\jinput.jar" @sources.txt
if errorlevel 1 (
  del sources.txt
  goto :eof
)
del sources.txt

rem NOTE: lwjgl / jinput are compile-time dependencies only, NOT bundled into the jar.
rem They are loaded as external libraries at runtime via run.bat -cp.
echo Packaging dungeon-finder.jar ...
jar cfe dungeon-finder.jar dungeon.app.DungeonMapApp -C build .

echo Done. Run with run.bat [seed]
endlocal