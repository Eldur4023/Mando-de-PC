#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XTest.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <jpeglib.h>
#include <vector>
#include <pthread.h>

#define PORT 5555
#define QUALITY 75
#define FPS 30

struct ClientContext {
    Display* display;
    Window root;
    int client_sock;
    int screen_width;
    int screen_height;
};

// Capturar pantalla X11
XImage* capture_screen(Display* display, Window root, int width, int height) {
    return XGetImage(display, root, 0, 0, width, height, AllPlanes, ZPixmap);
}

// Comprimir XImage a JPEG en memoria
std::vector<unsigned char> compress_to_jpeg(XImage* image, int quality) {
    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    
    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_compress(&cinfo);
    
    unsigned char* jpeg_buffer = nullptr;
    unsigned long jpeg_size = 0;
    
    jpeg_mem_dest(&cinfo, &jpeg_buffer, &jpeg_size);
    
    cinfo.image_width = image->width;
    cinfo.image_height = image->height;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    jpeg_start_compress(&cinfo, TRUE);
    
    // Convertir BGRA/RGBA a RGB
    std::vector<unsigned char> row_buffer(image->width * 3);
    
    while (cinfo.next_scanline < cinfo.image_height) {
        unsigned char* src = (unsigned char*)image->data + 
                            (cinfo.next_scanline * image->bytes_per_line);
        
        for (int x = 0; x < image->width; x++) {
            // Asumiendo formato BGRA (común en X11)
            row_buffer[x * 3 + 0] = src[x * 4 + 2]; // R
            row_buffer[x * 3 + 1] = src[x * 4 + 1]; // G
            row_buffer[x * 3 + 2] = src[x * 4 + 0]; // B
        }
        
        unsigned char* row_ptr = row_buffer.data();
        jpeg_write_scanlines(&cinfo, &row_ptr, 1);
    }
    
    jpeg_finish_compress(&cinfo);
    
    std::vector<unsigned char> result(jpeg_buffer, jpeg_buffer + jpeg_size);
    
    free(jpeg_buffer);
    jpeg_destroy_compress(&cinfo);
    
    return result;
}

// Enviar frame al cliente
bool send_frame(int client_sock, const std::vector<unsigned char>& jpeg_data) {
    // Enviar tamaño del frame (4 bytes)
    uint32_t size = jpeg_data.size();
    if (send(client_sock, &size, sizeof(size), 0) != sizeof(size)) {
        return false;
    }
    
    // Enviar datos JPEG
    size_t sent = 0;
    while (sent < jpeg_data.size()) {
        ssize_t n = send(client_sock, jpeg_data.data() + sent, 
                        jpeg_data.size() - sent, 0);
        if (n <= 0) return false;
        sent += n;
    }
    
    return true;
}

// Simular tecla usando XTest (acepta nombre de keysym X11 como "BackSpace", "space", "Return", etc.)
void simulate_keyname(Display* display, const char* name) {
    KeySym keysym = XStringToKeysym(name);
    if (keysym == NoSymbol) return;
    KeyCode keycode = XKeysymToKeycode(display, keysym);
    if (keycode == 0) return;
    XTestFakeKeyEvent(display, keycode, True, 0);
    XTestFakeKeyEvent(display, keycode, False, 0);
    XFlush(display);
}

void simulate_key(Display* display, char key) {
    char key_str[2] = {key, '\0'};
    simulate_keyname(display, key_str);
}

// Procesar un único comando
void process_command(ClientContext* ctx, const char* cmd) {
    if (strncmp(cmd, "MOUSE", 5) == 0) {
        int dx, dy;
        if (sscanf(cmd, "MOUSE %d %d", &dx, &dy) == 2) {
            // Movimiento relativo: None como src y dst mueve por delta
            XWarpPointer(ctx->display, None, None, 0, 0, 0, 0, dx, dy);
            XFlush(ctx->display);
        }
    } else if (strncmp(cmd, "CLICK", 5) == 0) {
        int button;
        if (sscanf(cmd, "CLICK %d", &button) == 1) {
            XTestFakeButtonEvent(ctx->display, button, True, 0);
            XTestFakeButtonEvent(ctx->display, button, False, 0);
            XFlush(ctx->display);
        }
    } else if (strncmp(cmd, "KEY", 3) == 0) {
        char key[64];
        if (sscanf(cmd, "KEY %63s", key) == 1) {
            if (strlen(key) > 1) {
                // Nombre de keysym X11 (p.ej. "BackSpace", "space", "Return")
                simulate_keyname(ctx->display, key);
            } else {
                simulate_key(ctx->display, key[0]);
            }
        }
    } else if (strncmp(cmd, "SCROLL", 6) == 0) {
        int direction;
        if (sscanf(cmd, "SCROLL %d", &direction) == 1) {
            int button = (direction > 0) ? 4 : 5;
            XTestFakeButtonEvent(ctx->display, button, True, 0);
            XTestFakeButtonEvent(ctx->display, button, False, 0);
            XFlush(ctx->display);
        }
    }
}

// Thread para manejar entrada del cliente (mouse/teclado)
// Protocolo:
//   0x01 + int16be dx + int16be dy  →  mouse move (binario, 5 bytes)
//   texto + '\n'                    →  CLICK/KEY/SCROLL (texto, comandos raros)
void* handle_client_input(void* arg) {
    ClientContext* ctx = (ClientContext*)arg;
    bool running = true;

    while (running) {
        uint8_t type;
        if (recv(ctx->client_sock, &type, 1, 0) <= 0) break;

        if (type == 0x01) {
            // Mouse binario: 4 bytes big-endian (int16 dx, int16 dy)
            uint8_t raw[4];
            if (recv(ctx->client_sock, raw, 4, MSG_WAITALL) != 4) break;
            int16_t dx = (int16_t)((raw[0] << 8) | raw[1]);
            int16_t dy = (int16_t)((raw[2] << 8) | raw[3]);
            XWarpPointer(ctx->display, None, None, 0, 0, 0, 0, (int)dx, (int)dy);
            XFlush(ctx->display);
        } else {
            // Comando de texto: leer hasta '\n'
            char line[256];
            line[0] = (char)type;
            int len = 1;
            while (len < (int)sizeof(line) - 1) {
                uint8_t ch;
                if (recv(ctx->client_sock, &ch, 1, 0) <= 0) { running = false; break; }
                if (ch == '\n') break;
                line[len++] = (char)ch;
            }
            if (running && len > 0) {
                line[len] = '\0';
                process_command(ctx, line);
            }
        }
    }
    return nullptr;
}

// Thread principal de streaming
void* stream_screen(void* arg) {
    ClientContext* ctx = (ClientContext*)arg;
    
    struct timespec sleep_time;
    sleep_time.tv_sec = 0;
    sleep_time.tv_nsec = 1000000000 / FPS;
    
    printf("Starting screen streaming at %d FPS...\n", FPS);
    
    int frame_count = 0;
    while (true) {
        // Capturar pantalla
        XImage* image = capture_screen(ctx->display, ctx->root, 
                                       ctx->screen_width, ctx->screen_height);
        if (!image) {
            fprintf(stderr, "Failed to capture screen\n");
            break;
        }
        
        // Comprimir a JPEG
        std::vector<unsigned char> jpeg_data = compress_to_jpeg(image, QUALITY);
        XDestroyImage(image);
        
        // Enviar al cliente
        if (!send_frame(ctx->client_sock, jpeg_data)) {
            fprintf(stderr, "Failed to send frame, client disconnected\n");
            break;
        }
        
        frame_count++;
        if (frame_count % 30 == 0) {
            printf("Frame %d sent: %lu bytes\n", frame_count, jpeg_data.size());
        }
        
        // Control de FPS
        nanosleep(&sleep_time, nullptr);
    }
    
    close(ctx->client_sock);
    return nullptr;
}

// Devuelve true solo si la IP es de red local (RFC 1918) o loopback
// ── Servidor Bluetooth RFCOMM ────────────────────────────────────────────────
#ifdef HAVE_BLUETOOTH
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <bluetooth/sdp.h>
#include <bluetooth/sdp_lib.h>

struct ServerShared { Display* display; Window root; int width; int height; };

static sdp_session_t* register_sdp_service(uint8_t ch) {
    sdp_record_t* rec = sdp_record_alloc();

    uuid_t svc, root_u, l2cap_u, rfcomm_u;
    sdp_uuid16_create(&svc,     SERIAL_PORT_SVCLASS_ID);
    sdp_set_service_id(rec, svc);

    sdp_uuid16_create(&root_u,  PUBLIC_BROWSE_GROUP);
    sdp_list_t* root_l = sdp_list_append(NULL, &root_u);
    sdp_set_browse_groups(rec, root_l);

    sdp_uuid16_create(&l2cap_u, L2CAP_UUID);
    sdp_list_t* l2_l  = sdp_list_append(NULL, &l2cap_u);
    sdp_list_t* proto = sdp_list_append(NULL, l2_l);

    sdp_uuid16_create(&rfcomm_u, RFCOMM_UUID);
    sdp_data_t* ch_d   = sdp_data_alloc(SDP_UINT8, &ch);
    sdp_list_t* rfcomm_l = sdp_list_append(NULL, &rfcomm_u);
    sdp_list_append(rfcomm_l, ch_d);
    sdp_list_append(proto, rfcomm_l);

    sdp_list_t* ap = sdp_list_append(NULL, proto);
    sdp_set_access_protos(rec, ap);
    sdp_set_info_attr(rec, "Mando de PC", NULL, NULL);

    bdaddr_t any  = {{0,0,0,0,0,0}};
    bdaddr_t local = {{0,0,0,0xff,0xff,0xff}};
    sdp_session_t* sess = sdp_connect(&any, &local, SDP_RETRY_IF_BUSY);
    if (sess) sdp_record_register(sess, rec, 0);

    sdp_data_free(ch_d);
    sdp_list_free(l2_l, NULL); sdp_list_free(rfcomm_l, NULL);
    sdp_list_free(root_l, NULL); sdp_list_free(proto, NULL);
    sdp_list_free(ap, NULL); sdp_record_free(rec);
    return sess;
}

void* bluetooth_server_thread(void* arg) {
    ServerShared* sh = (ServerShared*)arg;

    // Configurar adaptador ANTES de crear sockets (power on puede resetear estado BT)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-result"
    system("bluetoothctl power on 2>/dev/null");
    system("bluetoothctl system-alias 'Mando de PC' 2>/dev/null");
    system("bluetoothctl discoverable on 2>/dev/null");
    system("bluetoothctl pairable on 2>/dev/null");
    system("bluetoothctl discoverable-timeout 0 2>/dev/null");
#pragma GCC diagnostic pop
    sleep(1); // Dar tiempo al adaptador para estabilizarse

    int bt_sock = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    if (bt_sock < 0) { printf("Bluetooth no disponible\n"); return NULL; }

    int reuse = 1;
    setsockopt(bt_sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    // Buscar primer canal libre (bluetoothd puede ocupar los bajos)
    struct sockaddr_rc addr = {};
    addr.rc_family = AF_BLUETOOTH;
    bdaddr_t any_addr = {{0,0,0,0,0,0}};
    addr.rc_bdaddr = any_addr;

    uint8_t bound_ch = 0;
    for (uint8_t try_ch = 1; try_ch <= 30; try_ch++) {
        addr.rc_channel = try_ch;
        if (bind(bt_sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            bound_ch = try_ch;
            break;
        }
        printf("Canal RFCOMM %d ocupado\n", try_ch);
    }
    if (bound_ch == 0) {
        printf("No se pudo bind en ningun canal RFCOMM (1-30)\n");
        close(bt_sock); return NULL;
    }
    if (listen(bt_sock, 1) < 0) {
        perror("BT listen"); close(bt_sock); return NULL;
    }

    // NO usar sdptool (bluetoothd interceptaría la conexión).
    // Solo BlueZ C API: registra en SDP daemon sin activar ningún handler de bluetoothd.
    sdp_session_t* sdp = register_sdp_service(bound_ch);
    printf("Bluetooth RFCOMM escuchando en canal %d (SPP)...\n", bound_ch);

    while (true) {
        int client = accept(bt_sock, NULL, NULL);
        if (client < 0) break;
        printf("Cliente Bluetooth conectado\n");
        // Sin stream de video: solo comandos
        ClientContext* ctx = new ClientContext{sh->display, sh->root, client, sh->width, sh->height};
        pthread_t t;
        pthread_create(&t, nullptr, handle_client_input, ctx);
        pthread_join(t, nullptr);
        delete ctx; close(client);
        printf("Cliente Bluetooth desconectado\n");
    }

    if (sdp) sdp_close(sdp);
    close(bt_sock);
    return NULL;
}
#endif // HAVE_BLUETOOTH

bool is_local_ip(struct in_addr addr) {
    uint32_t ip = ntohl(addr.s_addr);
    return ((ip & 0xFF000000) == 0x7F000000) ||  // 127.0.0.0/8  loopback
           ((ip & 0xFF000000) == 0x0A000000) ||  // 10.0.0.0/8
           ((ip & 0xFFF00000) == 0xAC100000) ||  // 172.16.0.0/12
           ((ip & 0xFFFF0000) == 0xC0A80000);    // 192.168.0.0/16
}

int main() {
    setvbuf(stdout, NULL, _IOLBF, 0); // Line-buffered: cada printf aparece en log inmediatamente
    setvbuf(stderr, NULL, _IONBF, 0); // Stderr sin buffer
    // Necesario para usar X11 desde múltiples threads
    XInitThreads();

    // Inicializar conexión X11
    Display* display = XOpenDisplay(nullptr);
    if (!display) {
        fprintf(stderr, "Cannot open X display\n");
        return 1;
    }
    
    // Verificar extensión XTest
    int event_base, error_base, major, minor;
    if (!XTestQueryExtension(display, &event_base, &error_base, &major, &minor)) {
        fprintf(stderr, "XTest extension not available!\n");
        XCloseDisplay(display);
        return 1;
    }
    printf("XTest extension available (v%d.%d)\n", major, minor);
    
    int screen = DefaultScreen(display);
    Window root = RootWindow(display, screen);
    int width = DisplayWidth(display, screen);
    int height = DisplayHeight(display, screen);
    
    printf("Screen size: %dx%d\n", width, height);

#ifdef HAVE_BLUETOOTH
    static ServerShared bt_shared = {display, root, width, height};
    pthread_t bt_thread;
    if (pthread_create(&bt_thread, nullptr, bluetooth_server_thread, &bt_shared) == 0)
        pthread_detach(bt_thread);
#endif

    // Crear socket servidor
    int server_sock = socket(AF_INET, SOCK_STREAM, 0);
    if (server_sock < 0) {
        perror("socket");
        return 1;
    }
    
    int opt = 1;
    setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(PORT);
    
    if (bind(server_sock, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        perror("bind");
        return 1;
    }
    
    if (listen(server_sock, 1) < 0) {
        perror("listen");
        return 1;
    }
    
    printf("Server listening on port %d\n", PORT);
    printf("Waiting for client connection...\n");
    
    while (true) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        
        int client_sock = accept(server_sock, (struct sockaddr*)&client_addr, &client_len);
        if (client_sock < 0) {
            perror("accept");
            continue;
        }
        
        if (!is_local_ip(client_addr.sin_addr)) {
            fprintf(stderr, "Conexión rechazada desde IP externa: %s\n",
                    inet_ntoa(client_addr.sin_addr));
            close(client_sock);
            continue;
        }

        printf("Client connected from %s\n", inet_ntoa(client_addr.sin_addr));

        // Deshabilitar Nagle: envío inmediato de paquetes pequeños (menos latencia)
        int nodelay = 1;
        setsockopt(client_sock, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

        // Enviar dimensiones de pantalla al cliente
        uint32_t dims[2] = {(uint32_t)width, (uint32_t)height};
        send(client_sock, dims, sizeof(dims), 0);
        
        // Crear contexto para threads
        ClientContext* ctx = new ClientContext{display, root, client_sock, width, height};
        
        // Crear threads para streaming y manejo de entrada
        pthread_t stream_thread, input_thread;
        pthread_create(&stream_thread, nullptr, stream_screen, ctx);
        pthread_create(&input_thread, nullptr, handle_client_input, ctx);
        
        // Esperar a que terminen
        pthread_join(stream_thread, nullptr);
        pthread_cancel(input_thread);
        pthread_join(input_thread, nullptr);
        
        delete ctx;
        printf("Client disconnected\n");
    }
    
    close(server_sock);
    XCloseDisplay(display);
    
    return 0;
}
