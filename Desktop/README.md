# Remote Screen Control - Linux to Android

Sistema personalizado de control remoto de escritorio Linux desde Android.

## 🚀 Características

- ✅ Streaming de pantalla en tiempo real (compresión JPEG)
- ✅ Control de ratón desde pantalla táctil
- ✅ Transmisión por red local (TCP)
- ✅ ~30 FPS con compresión ajustable
- ⚠️ Sin encriptación (solo para redes locales confiables)

## 📋 Requisitos

### Servidor (Linux)
- Ubuntu/Debian/Linux Mint con X11
- Librerías: X11, JPEG, pthread
- Compilador C++ con soporte C++11

### Cliente (Android)
- Android Studio
- Dispositivo Android 5.0+ o emulador

## 🔧 Instalación - Servidor

```bash
# 1. Instalar dependencias
make install-deps

# 2. Compilar
make

# 3. Ejecutar
./screen_server
```

El servidor escuchará en el puerto **5555** y mostrará:
```
Screen size: 1920x1080
Server listening on port 5555
Waiting for client connection...
```

## 📱 Instalación - Cliente Android

1. Abrir Android Studio
2. Crear nuevo proyecto con "Empty Activity"
3. Copiar `MainActivity.java` a `app/src/main/java/com/example/remotescreen/`
4. Copiar `activity_main.xml` a `app/src/main/res/layout/`
5. Añadir permiso en `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET"/>
```

6. Compilar y ejecutar en dispositivo Android

## 🎮 Uso

1. **En el portátil**: Ejecuta `./screen_server`
2. **Averigua tu IP local**: `ip addr show` o `hostname -I`
3. **En el móvil**: 
   - Ingresa la IP del servidor (ej: 192.168.1.100)
   - Pulsa "Conectar"
   - ¡Mueve el dedo por la pantalla para controlar el ratón!

## ⚙️ Configuración

Edita estas constantes en `screen_server.cpp`:

```cpp
#define PORT 5555        // Puerto TCP
#define QUALITY 75       // Calidad JPEG (1-100)
#define FPS 30           // Frames por segundo
```

## 🔍 Protocolo de comunicación

### Servidor → Cliente
1. Dimensiones pantalla: `[width:uint32][height:uint32]`
2. Por cada frame: `[size:uint32][jpeg_data:bytes]`

### Cliente → Servidor
- Movimiento ratón: `"MOUSE x y\n"`
- Click: `"CLICK\n"`
- Tecla: `"KEY c\n"`

## 🐛 Troubleshooting

**Error: Cannot open X display**
- Verifica que estás en una sesión X11: `echo $DISPLAY`
- Intenta: `export DISPLAY=:0`

**Error: Failed to capture screen**
- Puede ser restricción de Wayland
- Usa X11 en su lugar

**Cliente no conecta**
- Verifica que estén en la misma red WiFi
- Comprueba firewall: `sudo ufw allow 5555/tcp`
- Prueba conexión: `telnet IP_SERVIDOR 5555`

**Baja calidad de imagen**
- Aumenta `QUALITY` (más CPU, más ancho de banda)
- Reduce `FPS` para mejor compresión

## 🚧 Limitaciones actuales

- ❌ Sin soporte para clicks (requiere XTest extension)
- ❌ Sin teclado virtual implementado
- ❌ Sin encriptación (no usar en redes públicas)
- ❌ Solo soporta X11 (no Wayland nativo)
- ❌ Un solo cliente a la vez

## 🔮 Mejoras futuras

1. **Seguridad**: Añadir SSL/TLS
2. **Input completo**: Implementar XTest para clicks y teclado
3. **Wayland**: Soporte para wlroots/PipeWire
4. **Compresión**: H.264/H.265 por hardware
5. **Audio**: Streaming de audio con PulseAudio
6. **Multi-monitor**: Selección de pantalla

## 📚 Referencias técnicas

- X11: https://www.x.org/releases/current/doc/libX11/libX11/libX11.html
- libjpeg: https://libjpeg.sourceforge.net/
- Socket programming: https://beej.us/guide/bgnet/

## 📄 Licencia

Código de ejemplo educativo - úsalo libremente
