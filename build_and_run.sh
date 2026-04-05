#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
DESKTOP_DIR="$PROJECT_DIR/Desktop"
APK_SRC="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_DST="$PROJECT_DIR/MandoDePC.apk"
SERVER_SRC="$DESKTOP_DIR/screen_server_full.cpp"
SERVER_BIN="$DESKTOP_DIR/screen_server_full"
SERVICE_NAME="mando-pc"

echo "╔══════════════════════════════════════╗"
echo "║         Mando de PC - Build          ║"
echo "╚══════════════════════════════════════╝"

# ── 1. Compilar APK ──────────────────────────────────────────────────────────
echo ""
echo "▶ Compilando APK..."
cd "$PROJECT_DIR"
./gradlew assembleDebug --quiet

cp "$APK_SRC" "$APK_DST"
echo "✔ APK generado: $APK_DST"

# ── 2. Compilar servidor ──────────────────────────────────────────────────────
echo ""
echo "▶ Compilando servidor..."
cd "$DESKTOP_DIR"
g++ -std=c++11 -O2 -Wall \
    -o "$SERVER_BIN" \
    "$SERVER_SRC" \
    -lX11 -lXext -lXtst -ljpeg -lpthread
echo "✔ Servidor compilado: $SERVER_BIN"

# ── 3. Lanzar servidor como demonio ───────────────────────────────────────────
echo ""
echo "▶ Lanzando servidor..."

# Parar instancia anterior si existe
if systemctl --user is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
    systemctl --user restart "$SERVICE_NAME"
    echo "✔ Servicio systemd reiniciado"
elif [ -f "$HOME/.config/systemd/user/$SERVICE_NAME.service" ]; then
    systemctl --user start "$SERVICE_NAME"
    echo "✔ Servicio systemd iniciado"
else
    # Matar instancia anterior si hay
    pkill -f screen_server_full 2>/dev/null || true
    sleep 0.5

    DISPLAY="${DISPLAY:-:0}" \
    XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}" \
    nohup "$SERVER_BIN" > "$DESKTOP_DIR/server.log" 2>&1 &

    echo "✔ Servidor lanzado en segundo plano (PID $!)"
    echo "  Logs: $DESKTOP_DIR/server.log"
fi

# ── Resumen ───────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════╗"
echo "║               Listo                  ║"
echo "╠══════════════════════════════════════╣"
printf  "║  APK:  %-30s ║\n" "MandoDePC.apk"
printf  "║  IP:   %-30s ║\n" "$(hostname -I | awk '{print $1}')"
printf  "║  Puerto: %-28s ║\n" "5555"
echo "╚══════════════════════════════════════╝"
