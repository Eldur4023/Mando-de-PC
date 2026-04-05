#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XShm.h>
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

// Thread para manejar entrada del cliente (mouse/teclado)
void* handle_client_input(void* arg) {
    ClientContext* ctx = (ClientContext*)arg;
    
    char buffer[256];
    while (true) {
        ssize_t n = recv(ctx->client_sock, buffer, sizeof(buffer) - 1, 0);
        if (n <= 0) break;
        
        buffer[n] = '\0';
        
        // Parsear comandos: "MOUSE x y" o "KEY code"
        if (strncmp(buffer, "MOUSE", 5) == 0) {
            int x, y;
            if (sscanf(buffer, "MOUSE %d %d", &x, &y) == 2) {
                // Mover cursor del ratón
                XWarpPointer(ctx->display, None, ctx->root, 0, 0, 0, 0, x, y);
                XFlush(ctx->display);
                printf("Mouse moved to: %d, %d\n", x, y);
            }
        } else if (strncmp(buffer, "CLICK", 5) == 0) {
            // Simular click (requiere XTest extension)
            printf("Click received\n");
        } else if (strncmp(buffer, "KEY", 3) == 0) {
            char key;
            if (sscanf(buffer, "KEY %c", &key) == 1) {
                printf("Key pressed: %c\n", key);
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
    sleep_time.tv_nsec = 1000000000 / FPS; // Nanosegundos por frame
    
    printf("Starting screen streaming at %d FPS...\n", FPS);
    
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
        
        printf("Sent frame: %lu bytes\n", jpeg_data.size());
        
        // Control de FPS
        nanosleep(&sleep_time, nullptr);
    }
    
    close(ctx->client_sock);
    return nullptr;
}

int main() {
    // Inicializar conexión X11
    Display* display = XOpenDisplay(nullptr);
    if (!display) {
        fprintf(stderr, "Cannot open X display\n");
        return 1;
    }
    
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
