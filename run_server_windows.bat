@echo off
setlocal

echo.
echo ========================================
echo   Mando de PC - Windows Server Setup
echo ========================================
echo.

:: 1. Verificar Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python no esta instalado o no esta en el PATH.
    echo Por favor, instala Python 3.9+ desde python.org
    pause
    exit /b 1
)

:: 2. Crear entorno virtual si no existe
if not exist "venv" (
    echo [*] Creando entorno virtual Python...
    python -m venv venv
)

:: 3. Instalar dependencias
echo [*] Instalando dependencias (mss, pynput, Pillow)...
call venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install mss pynput Pillow

if %errorlevel% neq 0 (
    echo [ERROR] Fallo al instalar dependencias.
    pause
    exit /b 1
)

:: 4. Obtener IP local para mostrar al usuario
echo.
echo ========================================
echo   TU IP LOCAL (Usa una de estas en la App):
echo ========================================
ipconfig | findstr /i "IPv4"
echo ========================================
echo.

:: 5. Ejecutar servidor
echo [+] Iniciando servidor...
python server_windows.py

pause
