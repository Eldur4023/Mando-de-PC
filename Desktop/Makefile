# Makefile para screen_server

CXX = g++
CXXFLAGS = -std=c++11 -O2 -Wall
LIBS = -lX11 -lXext -lXtst -ljpeg -lpthread

TARGET_BASIC = screen_server
TARGET_FULL = screen_server_full
SRC_BASIC = screen_server.cpp
SRC_FULL = screen_server_full.cpp

all: $(TARGET_FULL)

basic: $(TARGET_BASIC)
	@echo "Versión básica compilada (sin clicks/teclado)"

$(TARGET_BASIC): $(SRC_BASIC)
	$(CXX) $(CXXFLAGS) -o $(TARGET_BASIC) $(SRC_BASIC) -lX11 -lXext -ljpeg -lpthread

full: $(TARGET_FULL)
	@echo "Versión completa compilada (con clicks y teclado)"

$(TARGET_FULL): $(SRC_FULL)
	$(CXX) $(CXXFLAGS) -o $(TARGET_FULL) $(SRC_FULL) $(LIBS)

clean:
	rm -f $(TARGET_BASIC) $(TARGET_FULL)

install-deps:
	@echo "Instalando dependencias..."
	sudo apt-get update
	sudo apt-get install -y libx11-dev libxext-dev libxtst-dev libjpeg-dev build-essential

run: $(TARGET_FULL)
	./$(TARGET_FULL)

run-basic: $(TARGET_BASIC)
	./$(TARGET_BASIC)

.PHONY: all basic full clean install-deps run run-basic
