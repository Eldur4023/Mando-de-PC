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

BT_FLAGS=""
if [ -f /usr/include/bluetooth/bluetooth.h ]; then
    BT_FLAGS="-DHAVE_BLUETOOTH -lbluetooth"
    echo "  Bluetooth habilitado"
else
    echo "  Bluetooth no disponible (instala: sudo apt install libbluetooth-dev)"
fi

g++ -std=c++11 -O2 -Wall \
    -o "$SERVER_BIN" \
    "$SERVER_SRC" \
    -lX11 -lXext -lXtst -ljpeg -lpthread $BT_FLAGS
echo "✔ Servidor compilado: $SERVER_BIN"

# ── 3. Configurar Bluetooth ───────────────────────────────────────────────────
if [ -n "$BT_FLAGS" ]; then
    echo ""
    echo "▶ Configurando Bluetooth..."
    bluetoothctl power on 2>/dev/null || true
    bluetoothctl system-alias "Mando de PC" 2>/dev/null || true
    bluetoothctl discoverable on 2>/dev/null || true
    bluetoothctl pairable on 2>/dev/null || true
    bluetoothctl discoverable-timeout 0 2>/dev/null || true
    echo "✔ Bluetooth encendido y visible como 'Mando de PC'"
fi

# ── 4. Lanzar servidor como demonio ───────────────────────────────────────────
echo ""
echo "▶ Lanzando servidor..."

# Matar instancia anterior y liberar canal RFCOMM
pkill -f screen_server_full 2>/dev/null || true
rfcomm release all 2>/dev/null || true
sleep 1

DISPLAY="${DISPLAY:-:0}" \
XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}" \
nohup "$SERVER_BIN" > "$DESKTOP_DIR/server.log" 2>&1 &

SERVER_PID=$!
sleep 1
if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "✔ Servidor lanzado (PID $SERVER_PID)"
    echo "  Logs: $DESKTOP_DIR/server.log"
else
    echo "✘ Servidor falló al arrancar — ver: $DESKTOP_DIR/server.log"
    cat "$DESKTOP_DIR/server.log"
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
