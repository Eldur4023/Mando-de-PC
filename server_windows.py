import socket
import threading
import struct
import time
import sys

# Intenta importar pynput, fallará fuera de windows si no está instalado
try:
    from pynput.mouse import Button, Controller as MouseController
    from pynput.keyboard import Key, Controller as KeyboardController
    mouse = MouseController()
    keyboard = KeyboardController()
except ImportError:
    mouse = None
    keyboard = None
    print("Warning: pynput not installed. Input emulation will not work.")

PORT = 5555
QUALITY = 70
FPS = 30

# Mapeo de teclas especiales para pynput
KEY_MAP = {
    "space": " ",
    "Return": Key.enter,
    "BackSpace": Key.backspace,
    "Escape": Key.esc,
    "Tab": Key.tab,
    "Control_L": Key.ctrl_l,
    "Alt_L": Key.alt_l,
    "Shift_L": Key.shift_l,
}

def handle_client_input(client_sock):
    print("Manejando comandos del cliente...")
    while True:
        try:
            raw_type = client_sock.recv(1)
            if not raw_type: break
            type_byte = raw_type[0]

            if type_byte == 0x01: # Movimiento Binario (5 bytes: 0x01 + dx_be16 + dy_be16)
                raw = client_sock.recv(4)
                if len(raw) < 4: break
                dx, dy = struct.unpack(">hh", raw)
                if mouse: mouse.move(dx, dy)
            else:
                # Comando de texto: MOUSE, CLICK, KEY, SCROLL
                line = chr(type_byte)
                while True:
                    ch = client_sock.recv(1)
                    if not ch or ch == b'\n': break
                    line += ch.decode('ascii', errors='ignore')
                
                process_command(line)
        except Exception as e:
            print(f"Error en entrada: {e}")
            break
    print("Hilo de entrada cerrado")

def process_command(cmd):
    if not mouse or not keyboard: return
    try:
        parts = cmd.split()
        if not parts: return
        action = parts[0]

        if action == "MOUSE":
            dx, dy = int(parts[1]), int(parts[2])
            mouse.move(dx, dy)
        elif action == "CLICK":
            btn_idx = parts[1]
            btn = Button.left if btn_idx == "1" else Button.right if btn_idx == "3" else Button.middle
            mouse.click(btn)
        elif action == "KEY":
            key_name = parts[1]
            if key_name in KEY_MAP:
                k = KEY_MAP[key_name]
                keyboard.press(k); keyboard.release(k)
            elif len(key_name) == 1:
                keyboard.press(key_name); keyboard.release(key_name)
        elif action == "SCROLL":
            direction = int(parts[1])
            # En pynput, scroll(0, 1) es hacia arriba
            mouse.scroll(0, direction)
    except Exception as e:
        print(f"Error procesando '{cmd}': {e}")

# (Streaming de pantalla eliminado)

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", PORT))
    except Exception as e:
        print(f"No se pudo bind al puerto {PORT}: {e}")
        return
        
    server.listen(1)
    print(f"Servidor Windows de Mando de PC rodando en puerto {PORT}...")
    print("Usa la IP de este PC en la app de Android.")

    try:
        while True:
            client, addr = server.accept()
            print(f"Conexión desde {addr}")
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            
            # Manejar solo entrada (teclado/ratón)
            handle_client_input(client)
            
            client.close()
            print("Cliente desconectado, esperando nuevo...")
    except KeyboardInterrupt:
        print("Servidor detenido por el usuario")
    finally:
        server.close()

if __name__ == "__main__":
    main()
