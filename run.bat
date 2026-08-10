@echo off
rem ============================================================
rem  Dungeon Finder - run the packaged jar
rem  Usage: run.bat [seed]
rem  e.g.   run.bat 8676641231682978167
rem  Requires: dungeon-finder.jar (built by compile.bat)
rem ============================================================
setlocal
cd /d "%~dp0"

if not exist dungeon-finder.jar (
  echo dungeon-finder.jar not found. Run compile.bat first.
  goto :eof
)

java "-Djava.library.path=natives" -jar dungeon-finder.jar %*

endlocal