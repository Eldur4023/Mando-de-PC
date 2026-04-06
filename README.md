# Mando de PC (Remote PC Controller)

Mando de PC es una aplicación de control remoto que permite usar tu dispositivo Android como un ratón y teclado para tu ordenador. Soporta tanto Linux (X11) como Windows, con conexiones vía WiFi y Bluetooth.

## Características

- **Ratón y Teclado**: Controla el cursor con gestos táctiles y escribe en tiempo real.
- **Protocolo de Alta Velocidad**: Implementa un protocolo binario y optimizaciones de hilo de prioridad para una latencia mínima.
- **Conectividad Dual**: Soporta TCP (WiFi) y RFCOMM (Bluetooth SPP).

### Servidor Linux (Ubuntu/Debian)
1. Instala las dependencias necesarias:
   ```bash
   sudo apt install libx11-dev libxtst-dev libbluetooth-dev g++
   ```
2. Compila y ejecuta el servidor usando el script incluido:
   ```bash
   ./build_and_run.sh
   ```
   Esto compilará el servidor C++ en `Desktop/screen_server_full.cpp` y lo dejará corriendo en segundo plano.

### Servidor Windows
1. Asegúrate de tener **Python 3.9+** instalado.
2. Copia los archivos `server_windows.py` y `run_server_windows.bat` a tu PC.
3. Ejecuta `run_server_windows.bat`. El script configurará automáticamente un entorno virtual e instalará las dependencias (`mss`, `pynput`).
4. Verás tu dirección IP en la consola; úsala para conectar desde la app.

## Detalles Técnicos y Optimizaciones

- **Protocolo Binario**: Los movimientos del ratón se envían en paquetes de 5 bytes (`0x01` + `dx` + `dy`) para minimizar el overhead de red.
- **Procesamiento Histórico**: La app Android procesa todos los puntos de contacto intermedios de `MotionEvent` para evitar tirones.
- **Zero-Allocation**: El formateador de paquetes en Android está optimizado para no crear objetos temporales, reduciendo la presión sobre el recolector de basura.
- **Threading**: El hilo de envío en Android tiene prioridad `URGENT_DISPLAY` para garantizar una respuesta instantánea (en modo WiFi).

## Licencia
Este proyecto es de uso libre bajo la licencia MIT.
