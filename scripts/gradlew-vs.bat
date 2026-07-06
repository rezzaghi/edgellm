@echo off
rem Runs Gradle inside a Visual Studio developer environment. Only needed on
rem Windows when building with the Vulkan backend enabled (its build compiles
rem a host tool with MSVC). Locates VS via vswhere, so no hardcoded paths.
rem Usage: scripts\gradlew-vs.bat <gradle tasks...>

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo vswhere.exe not found - is Visual Studio installed?
    exit /b 1
)

for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSDIR=%%i"
if not defined VSDIR (
    echo No Visual Studio with C++ tools found.
    exit /b 1
)

call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul
cd /d "%~dp0.."
call gradlew.bat %*
