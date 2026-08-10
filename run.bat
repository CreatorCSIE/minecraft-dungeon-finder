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

rem 以 -cp 方式运行，lwjgl / jinput 作为外置库加载（不打包进 dungeon-finder.jar）
java "-Djava.library.path=natives" -cp "dungeon-finder.jar;lib\lwjgl.jar;lib\lwjgl_util.jar;lib\jinput.jar" dungeon.app.DungeonMapApp %*

endlocal