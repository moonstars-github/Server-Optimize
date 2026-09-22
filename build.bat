@echo off
chcp 65001 >nul
set "JAVA_HOME=C:\Program Files\Java\latest\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=I:\Userdata\Serverfile\Mod Project\Server Optimize\tempfile\.gradle-home"
set "_JAVA_OPTIONS=-Djava.io.tmpdir=I:\Userdata\Serverfile\Mod Project\Server Optimize\tempfile\gradle-tmp"

echo JAVA_HOME: %JAVA_HOME%
java -version

echo Building...
call gradlew.bat --no-daemon build
if %errorlevel% neq 0 (
    echo BUILD FAILED with code %errorlevel%
    pause
    exit /b %errorlevel%
)
echo BUILD SUCCESS
pause
