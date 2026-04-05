#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XTest.h>
#include <sys/socket.h>
#include <netinet/in.h>
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

// Simular tecla usando XTest
void simulate_key(Display* display, char key) {
    char key_str[2] = {key, '\0'};  // null-terminated
    KeySym keysym = XStringToKeysym(key_str);
    if (keysym == NoSymbol) return;

    KeyCode keycode = XKeysymToKeycode(display, keysym);
    if (keycode == 0) return;

    XTestFakeKeyEvent(display, keycode, True, 0);  // Press
    XTestFakeKeyEvent(display, keycode, False, 0); // Release
    XFlush(display);
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
        if (sscanf(cmd, "KEY %s", key) == 1) {
            for (int i = 0; key[i]; i++) {
                simulate_key(ctx->display, key[i]);
            }
            printf("Key pressed: %s\n", key);
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
void* handle_client_input(void* arg) {
    ClientContext* ctx = (ClientContext*)arg;

    char buffer[4096];
    char leftover[4096] = "";

    while (true) {
        ssize_t n = recv(ctx->client_sock, buffer, sizeof(buffer) - 1, 0);
        if (n <= 0) break;
        buffer[n] = '\0';

        // Concatenar con lo que sobró antes (ambos <= 4095, caben en 8192)
        char combined[8192];
        snprintf(combined, sizeof(combined), "%s%s", leftover, buffer);
        leftover[0] = '\0';

        // Procesar línea por línea
        char* line = combined;
        char* newline;
        while ((newline = strchr(line, '\n')) != nullptr) {
            *newline = '\0';
            if (*line) process_command(ctx, line);
            line = newline + 1;
        }

        // Guardar lo que quedó sin '\n' para el siguiente recv
        if (*line) {
            // line apunta dentro de combined (<=8191 bytes), leftover y combined
            // tienen el mismo tamaño máximo útil, así que es seguro
            size_t len = strlen(line);
            if (len >= sizeof(leftover)) len = sizeof(leftover) - 1;
            memcpy(leftover, line, len);
            leftover[len] = '\0';
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
bool is_local_ip(struct in_addr addr) {
    uint32_t ip = ntohl(addr.s_addr);
    return ((ip & 0xFF000000) == 0x7F000000) ||  // 127.0.0.0/8  loopback
           ((ip & 0xFF000000) == 0x0A000000) ||  // 10.0.0.0/8
           ((ip & 0xFFF00000) == 0xAC100000) ||  // 172.16.0.0/12
           ((ip & 0xFFFF0000) == 0xC0A80000);    // 192.168.0.0/16
}

int main() {
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
