package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // Conexión (TCP o BT comparten las mismas streams)
    private var socket: Socket? = null
    private var btSocket: BluetoothSocket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var receiveJob: Job? = null
    private var isConnected = false
    private var isBtMode = false

    // Canal de comandos: el mouse sender es el único escritor del socket
    private val commandChannel = Channel<String>(Channel.UNLIMITED)
    private var mouseSenderJob: Job? = null

    // Acumuladores de movimiento
    private val accumDx = AtomicInteger(0)
    private val accumDy = AtomicInteger(0)
    private var touchRemDx = 0f
    private var touchRemDy = 0f
    private var touchpadSensitivity = 3.5f

    // Tap / doble tap / triple tap
    private val tapHandler = Handler(Looper.getMainLooper())
    private var pendingTapAction: Runnable? = null
    private var lastTapTime = 0L
    private var tapCount = 0
    private var touchMoved = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val TAP_MOVEMENT_THRESHOLD = 12f
    private val DOUBLE_TAP_MS = 280L

    // Scroll con dos dedos
    private var isScrollMode = false
    private var scrollPointerId = -1
    private var scrollLastY = 0f
    private var scrollAccum = 0f
    private val SCROLL_THRESHOLD = 30f

    // Teclado en tiempo real
    private var sentText = ""

    // Bluetooth
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var selectedBtDevice: BluetoothDevice? = null

    // Buffer para formateado ultra-rápido (cero alocaciones)
    private val fastBuffer = ByteArray(64)
    private fun writeIntToBuffer(value: Int, buffer: ByteArray, offset: Int): Int {
        var v = value; var off = offset
        if (v == 0) { buffer[off++] = '0'.toByte(); return off }
        if (v < 0) { buffer[off++] = '-'.toByte(); v = -v }
        val start = off
        while (v > 0) { buffer[off++] = ('0'.toInt() + (v % 10)).toByte(); v /= 10 }
        var s = start; var e = off - 1
        while (s < e) { val tmp = buffer[s]; buffer[s] = buffer[e]; buffer[e] = tmp; s++; e-- }
        return off
    }

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) showBtDevicePicker()
        else Toast.makeText(this, "Permisos Bluetooth requeridos", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("mando_prefs", MODE_PRIVATE)
        touchpadSensitivity = prefs.getFloat("touchpad_sensitivity", 3.5f)

        setupUI()
    }

    private fun setupUI() {
        // ── Modo WiFi / Bluetooth ─────────────────────────────────────────────
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isBtMode = (checkedId == R.id.btBtnMode)
            binding.wifiSection.visibility = if (isBtMode) View.GONE else View.VISIBLE
            binding.btSection.visibility   = if (isBtMode) View.VISIBLE else View.GONE
        }

        binding.btPickBtn.setOnClickListener { requestBtPermissionAndPick() }

        // ── Conectar ──────────────────────────────────────────────────────────
        binding.connectBtn.setOnClickListener {
            if (isBtMode) {
                val dev = selectedBtDevice
                if (dev == null) Toast.makeText(this, "Selecciona un dispositivo Bluetooth", Toast.LENGTH_SHORT).show()
                else connectViaBluetooth(dev)
            } else {
                val ip = binding.ipInput.text.toString()
                if (ip.isNotEmpty()) connectViaWifi(ip)
                else Toast.makeText(this, "Introduce una IP válida", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Zona squircle ─────────────────────────────────────────────────────
        binding.moverZone.setOnTouchListener { _, event ->
            if (!isConnected) return@setOnTouchListener false
            handleZone(event)
            true
        }

        // ── Teclado en tiempo real ────────────────────────────────────────────
        binding.keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                if (!isConnected) return
                val newText = editable?.toString() ?: ""
                if (newText.length > sentText.length) {
                    for (c in newText.substring(sentText.length)) when (c) {
                        ' '  -> sendCommand("KEY space\n")
                        '\n' -> sendCommand("KEY Return\n")
                        else -> sendCommand("KEY $c\n")
                    }
                } else if (newText.length < sentText.length) {
                    repeat(sentText.length - newText.length) { sendCommand("KEY BackSpace\n") }
                }
                sentText = newText
            }
        })

        binding.settingsBtn.setOnClickListener { showSettingsSheet() }
    }

    // ── Zona táctil ───────────────────────────────────────────────────────────

    private fun handleZone(event: MotionEvent) {
        if (isScrollMode) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Procesar puntos históricos para mayor fluidez
                for (i in 0 until event.historySize) {
                    updateMovement(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                updateMovement(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!touchMoved) {
                    val now = System.currentTimeMillis()
                    pendingTapAction?.let { tapHandler.removeCallbacks(it) }
                    tapCount = if (now - lastTapTime < DOUBLE_TAP_MS) tapCount + 1 else 1
                    lastTapTime = now
                    val count = tapCount
                    val action = Runnable {
                        when (count) {
                            1    -> sendCommand("CLICK 1\n")
                            2    -> { sendCommand("CLICK 1\n"); sendCommand("CLICK 1\n") }
                            else -> sendCommand("CLICK 3\n")
                        }
                        tapCount = 0
                    }
                    pendingTapAction = action
                    tapHandler.postDelayed(action, DOUBLE_TAP_MS)
                }
            }
        }
    }

    private fun updateMovement(newX: Float, newY: Float) {
        val dx = newX - lastTouchX
        val dy = newY - lastTouchY
        lastTouchX = newX
        lastTouchY = newY

        // Umbral de movimiento más sensible para el inicio (2f en vez de 12f)
        if (!touchMoved && (abs(dx) > 2f || abs(dy) > 2f)) {
            touchMoved = true
        }

        if (touchMoved) {
            val fx = dx * touchpadSensitivity + touchRemDx
            val fy = dy * touchpadSensitivity + touchRemDy
            val ix = fx.toInt(); val iy = fy.toInt()
            accumDx.addAndGet(ix); accumDy.addAndGet(iy)
            touchRemDx = fx - ix;  touchRemDy = fy - iy
        }
    }

    // ── Scroll de dos dedos ───────────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isConnected) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) {
                        isScrollMode = true
                        scrollAccum = 0f
                        scrollPointerId = ev.getPointerId(ev.actionIndex)
                        scrollLastY = ev.getY(ev.actionIndex)
                        pendingTapAction?.let { tapHandler.removeCallbacks(it) }
                        pendingTapAction = null
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScrollMode && ev.pointerCount >= 2) {
                        val idx = ev.findPointerIndex(scrollPointerId)
                        if (idx >= 0) {
                            val y = ev.getY(idx)
                            scrollAccum += y - scrollLastY
                            scrollLastY = y
                            while (scrollAccum >= SCROLL_THRESHOLD) {
                                sendCommand("SCROLL -1\n")
                                scrollAccum -= SCROLL_THRESHOLD
                            }
                            while (scrollAccum <= -SCROLL_THRESHOLD) {
                                sendCommand("SCROLL 1\n")
                                scrollAccum += SCROLL_THRESHOLD
                            }
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    isScrollMode = false; scrollPointerId = -1; scrollAccum = 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isScrollMode = false; scrollPointerId = -1; scrollAccum = 0f
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Conexión WiFi ─────────────────────────────────────────────────────────

    private fun connectViaWifi(ip: String) {
        binding.connectBtn.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                socket = Socket(ip, 5555).also { it.tcpNoDelay = true }
                inputStream  = DataInputStream(socket!!.getInputStream())
                outputStream = DataOutputStream(socket!!.getOutputStream())
                // Handshake: leer dimensiones de pantalla (ELIMINADO)
                // inputStream!!.readInt(); inputStream!!.readInt()
                withContext(Dispatchers.Main) { onConnected() }
                startMouseSender()
                waitForWifiDisconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.connectBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error WiFi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Conexión Bluetooth ────────────────────────────────────────────────────

    private fun connectViaBluetooth(device: BluetoothDevice) {
        binding.connectBtn.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
                
                // Espera de estabilización para el stack BT
                delay(500)

                val connectionMethods = listOf(
                    { // Método 1: Reflection hack (Channel 1)
                        @Suppress("DiscouragedPrivateApi")
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                        m.invoke(device, 1) as BluetoothSocket
                    },
                    { // Método 2: Insecure SPP
                        device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    },
                    { // Método 3: Secure SPP
                        device.createRfcommSocketToServiceRecord(SPP_UUID)
                    }
                )

                var lastError: Exception? = null
                for (method in connectionMethods) {
                    try {
                        btSocket?.close()
                        btSocket = method()
                        
                        // Reintentar conexión específica
                        var connected = false
                        repeat(2) {
                            try {
                                if (!connected) {
                                    btSocket!!.connect()
                                    connected = true
                                }
                            } catch (e: Exception) {
                                lastError = e
                                delay(500)
                            }
                        }
                        if (!connected) throw lastError ?: Exception("Failed to connect")

                        // Éxito
                        outputStream = DataOutputStream(btSocket!!.getOutputStream())
                        inputStream  = DataInputStream(btSocket!!.getInputStream())
                        withContext(Dispatchers.Main) { onConnected() }
                        startMouseSender()
                        waitForBtDisconnect()
                        return@launch
                    } catch (e: Exception) {
                        lastError = e
                    }
                }

                // Si fallaron todos los intentos
                withContext(Dispatchers.Main) {
                    binding.connectBtn.isEnabled = true
                    val msg = "Error Bluetooth: ${lastError?.message ?: "Desconocido"}"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.connectBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error fatal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun waitForBtDisconnect() {
        receiveJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(64)
            try {
                while (isActive && isConnected) {
                    if (inputStream!!.read(buf) < 0) break
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { handleDisconnect() }
        }
    }

    private fun requestBtPermissionAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                btPermissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        showBtDevicePicker()
    }

    private fun showBtDevicePicker() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val devices   = mutableListOf<BluetoothDevice>()
        val names     = mutableListOf<String>()
        val listAdapt = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)

        // Dispositivos ya emparejados (aparecen inmediatamente)
        btAdapter.bondedDevices?.forEach { dev ->
            devices.add(dev)
            names.add("${dev.name ?: dev.address}  ✓")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscando dispositivos…")
            .setAdapter(listAdapt) { _, i ->
                selectedBtDevice = devices[i]
                binding.btDeviceName.text = devices[i].name ?: devices[i].address
                btAdapter.cancelDiscovery()
            }
            .setNegativeButton("Cancelar") { _, _ -> btAdapter.cancelDiscovery() }
            .show()

        // Receptor para dispositivos descubiertos durante el escaneo
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val dev = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                dev ?: return
                if (devices.none { it.address == dev.address }) {
                    devices.add(dev)
                    names.add(dev.name ?: dev.address)
                    listAdapt.notifyDataSetChanged()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        }
        btAdapter.startDiscovery()

        dialog.setOnDismissListener {
            btAdapter.cancelDiscovery()
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    // ── Mouse sender (único escritor del socket) ──────────────────────────────

    private fun startMouseSender() {
        mouseSenderJob = lifecycleScope.launch(Dispatchers.IO) {
            // Prioridad ultra-alta solo para WiFi (BT es más sensible)
            if (!isBtMode) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            while (isActive && isConnected) {
                try {
                    var wrote = false
                    // 1. Vaciar comandos especiales (teclado/click)
                    var cmd = commandChannel.tryReceive().getOrNull()
                    while (cmd != null) {
                        outputStream?.writeBytes(cmd)
                        wrote = true
                        cmd = commandChannel.tryReceive().getOrNull()
                    }

                    // 2. Movimiento acumulado: Protocolo Binario (0x01 + dx_be16 + dy_be16)
                    val dx = accumDx.getAndSet(0).coerceIn(-32768, 32767)
                    val dy = accumDy.getAndSet(0).coerceIn(-32768, 32767)
                    if (dx != 0 || dy != 0) {
                        fastBuffer[0] = 0x01.toByte()
                        fastBuffer[1] = (dx shr 8).toByte()
                        fastBuffer[2] = (dx and 0xFF).toByte()
                        fastBuffer[3] = (dy shr 8).toByte()
                        fastBuffer[4] = (dy and 0xFF).toByte()
                        outputStream?.write(fastBuffer, 0, 5)
                        wrote = true
                    }

                    if (wrote) {
                        outputStream?.flush()
                        // Latencia adaptativa: BT requiere más tiempo entre paquetes
                        if (isBtMode) delay(8) else delay(1)
                    } else {
                        // Reposo para no malgastar batería
                        delay(12)
                    }
                } catch (_: Exception) { return@launch }
            }
        }
    }

    private fun waitForWifiDisconnect() {
        receiveJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            try {
                while (isActive && isConnected) {
                    if (inputStream!!.read(buf) < 0) break
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { handleDisconnect() }
        }
    }

    // ── Estado de conexión ────────────────────────────────────────────────────

    private fun onConnected() {
        isConnected = true
        binding.connectionBar.visibility   = View.GONE
        binding.connectedLayout.visibility = View.VISIBLE
        Toast.makeText(this, "Conectado", Toast.LENGTH_SHORT).show()
    }

    private fun handleDisconnect() {
        isConnected = false
        isScrollMode = false; scrollPointerId = -1
        accumDx.set(0); accumDy.set(0)
        touchRemDx = 0f; touchRemDy = 0f
        sentText = ""
        mouseSenderJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            try { socket?.close() } catch (_: Exception) {}
            try { btSocket?.close() } catch (_: Exception) {}
        }
        socket = null; btSocket = null
        binding.connectionBar.visibility   = View.VISIBLE
        binding.connectBtn.isEnabled       = true
        binding.connectedLayout.visibility = View.GONE
    }

    // ── Envío de comandos ─────────────────────────────────────────────────────

    private fun sendCommand(cmd: String) {
        commandChannel.trySend(cmd)
    }

    // ── Configuración ─────────────────────────────────────────────────────────

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        sheet.setContentView(view)
        val slider = view.findViewById<Slider>(R.id.touchpadSlider)
        val tv     = view.findViewById<TextView>(R.id.touchpadValue)
        slider.value = touchpadSensitivity.coerceIn(0.5f, 10.0f)
        tv.text = String.format("%.1f", touchpadSensitivity)
        slider.addOnChangeListener { _, value, _ ->
            touchpadSensitivity = value
            tv.text = String.format("%.1f", value)
            prefs.edit().putFloat("touchpad_sensitivity", value).apply()
        }
        sheet.show()
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        receiveJob?.cancel()
        pendingTapAction?.let { tapHandler.removeCallbacks(it) }
        lifecycleScope.launch(Dispatchers.IO) {
            try { socket?.close() } catch (_: Exception) {}
            try { btSocket?.close() } catch (_: Exception) {}
        }
    }
}
