#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_NAME="mando-pc"
SERVICE_DIR="$HOME/.config/systemd/user"

echo "=== Instalando $SERVICE_NAME como servicio de usuario ==="

# Compilar si el binario no existe o el fuente es más nuevo
if [ ! -f "$SCRIPT_DIR/screen_server_full" ] || \
   [ "$SCRIPT_DIR/screen_server_full.cpp" -nt "$SCRIPT_DIR/screen_server_full" ]; then
    echo "Compilando screen_server_full..."
    g++ -std=c++11 -O2 -Wall \
        -o "$SCRIPT_DIR/screen_server_full" \
        "$SCRIPT_DIR/screen_server_full.cpp" \
        -lX11 -lXext -lXtst -ljpeg -lpthread
    echo "Compilación exitosa."
fi

# Crear directorio de servicios de usuario si no existe
mkdir -p "$SERVICE_DIR"

# Copiar el archivo de servicio
cp "$SCRIPT_DIR/$SERVICE_NAME.service" "$SERVICE_DIR/$SERVICE_NAME.service"
echo "Servicio copiado a $SERVICE_DIR"

# Recargar systemd y habilitar el servicio
systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME"
systemctl --user start "$SERVICE_NAME"

echo ""
echo "=== Listo ==="
echo "El servidor arrancará automáticamente al iniciar sesión."
echo ""
echo "Comandos útiles:"
echo "  Ver estado:  systemctl --user status $SERVICE_NAME"
echo "  Ver logs:    journalctl --user -u $SERVICE_NAME -f"
echo "  Parar:       systemctl --user stop $SERVICE_NAME"
echo "  Deshabilitar: systemctl --user disable $SERVICE_NAME"
