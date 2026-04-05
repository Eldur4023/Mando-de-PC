package com.example.myapplication

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.BitmapFactory
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var receiveJob: Job? = null
    private var isConnected = false
    private val sendMutex = Mutex()

    // Sensor de gravedad (inclinación)
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var isMoving = false
    private val sensitivity = 2000f
    private val deadzone = 0.003f
    private var prevAzimuth = 0f
    private var prevPitch = 0f
    private var hasPrev = false

    // Acumuladores thread-safe: el sensor acumula, el sender envía a 70 Hz
    private val accumDx = AtomicInteger(0)
    private val accumDy = AtomicInteger(0)
    // Suavizado EMA para eliminar ruido del sensor
    private var smoothDx = 0f
    private var smoothDy = 0f
    private val emaAlpha = 0.45f   // 0 = sin suavizado, 1 = máximo suavizado
    private var mouseSenderJob: Job? = null

    // Tap / doble tap
    private val tapHandler = Handler(Looper.getMainLooper())
    private var pendingSingleTap: Runnable? = null
    private var lastTapTime = 0L
    private var touchMoved = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val TAP_MOVEMENT_THRESHOLD = 15f
    private val DOUBLE_TAP_MS = 280L
    private val touchpadSensitivity = 3.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // TYPE_GRAVITY = acelerómetro filtrado (solo inclinación, sin sacudidas)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        setupUI()
    }

    private fun setupUI() {
        binding.connectBtn.setOnClickListener {
            val ip = binding.ipInput.text.toString()
            if (ip.isNotEmpty()) connectToServer(ip)
            else Toast.makeText(this, "Introduce una IP válida", Toast.LENGTH_SHORT).show()
        }

        var moveBtnPressTime = 0L
        val TAP_MAX_MS = 200L
        binding.calibrateBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isMoving = true
                    moveBtnPressTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isMoving = false
                    if (System.currentTimeMillis() - moveBtnPressTime < TAP_MAX_MS) {
                        sendClick(1)
                    }
                }
            }
            true
        }

        binding.leftClickBtn.setOnClickListener { sendClick(1) }
        binding.rightClickBtn.setOnClickListener { sendClick(3) }

        binding.keyboardInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.keyboardInput.text.toString()
                if (text.isNotEmpty()) {
                    sendKeyPress(text)
                    binding.keyboardInput.text?.clear()
                }
                true
            } else false
        }

        binding.touchArea.setOnTouchListener { _, event ->
            if (!isConnected) return@setOnTouchListener false
            handleTap(event)
            true
        }
    }

    private fun handleTap(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY

                if (abs(dx) + abs(dy) > TAP_MOVEMENT_THRESHOLD) touchMoved = true

                // Touchpad: acumula el delta del dedo mientras Mover está pulsado
                if (isMoving && touchMoved) {
                    accumDx.addAndGet((dx * touchpadSensitivity).toInt())
                    accumDy.addAndGet((dy * touchpadSensitivity).toInt())
                }

                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < DOUBLE_TAP_MS) {
                        pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                        pendingSingleTap = null
                        lastTapTime = 0L
                        sendClick(3)
                    } else {
                        lastTapTime = now
                        val tap = Runnable { sendClick(1) }
                        pendingSingleTap = tap
                        tapHandler.postDelayed(tap, DOUBLE_TAP_MS)
                    }
                }
            }
        }
    }

    // ── Sensor ────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (!isConnected) return

        // values[0] = X: inclinación izq(-) / der(+)
        // values[2] = Z: inclinación atrás(-) / adelante(+)
        val x = event.values[0]
        val z = event.values[2]

        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMatrix, orientation)

        val azimuth = orientation[0]  // izq/der
        val pitch   = orientation[1]  // arriba/abajo

        if (!isMoving || !hasPrev) {
            prevAzimuth = azimuth
            prevPitch   = pitch
            hasPrev     = true
            return
        }

        var dAzimuth = azimuth - prevAzimuth
        var dPitch   = pitch   - prevPitch

        // Corregir salto de -π a +π
        if (dAzimuth >  Math.PI) dAzimuth -= (2 * Math.PI).toFloat()
        if (dAzimuth < -Math.PI) dAzimuth += (2 * Math.PI).toFloat()

        prevAzimuth = azimuth
        prevPitch   = pitch

        val rawDx = if (abs(dAzimuth) > deadzone) dAzimuth * sensitivity else 0f
        val rawDy = if (abs(dPitch)   > deadzone) dPitch   * sensitivity else 0f

        // EMA: suaviza picos bruscos del sensor
        smoothDx = smoothDx * emaAlpha + rawDx * (1f - emaAlpha)
        smoothDy = smoothDy * emaAlpha + rawDy * (1f - emaAlpha)

        // Acumular para que el sender los recoja en el próximo ciclo
        accumDx.addAndGet(smoothDx.toInt())
        accumDy.addAndGet(smoothDy.toInt())
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ── Conexión ──────────────────────────────────────────────────────────────

    private fun connectToServer(ip: String) {
        binding.connectBtn.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                socket = Socket(ip, 5555)
                inputStream = DataInputStream(socket!!.getInputStream())
                outputStream = DataOutputStream(socket!!.getOutputStream())

                // Leer y descartar dimensiones de pantalla (el servidor las envía siempre)
                inputStream!!.readInt()
                inputStream!!.readInt()

                withContext(Dispatchers.Main) {
                    isConnected = true
                    binding.connectionBar.visibility = View.GONE
                    binding.connectedLayout.visibility = View.VISIBLE

                    // Iniciar sensor
                    gravitySensor?.let {
                        sensorManager.registerListener(this@MainActivity, it, SensorManager.SENSOR_DELAY_GAME)
                    }

                    Toast.makeText(this@MainActivity, "Conectado", Toast.LENGTH_SHORT).show()
                }

                startMouseSender()
                drainFrames()

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.connectBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Envía los deltas acumulados a ~70 Hz, usando el mutex para no mezclar con clicks
    private fun startMouseSender() {
        mouseSenderJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                val dx = accumDx.getAndSet(0)
                val dy = accumDy.getAndSet(0)
                if (dx != 0 || dy != 0) {
                    sendMutex.withLock {
                        try {
                            outputStream?.writeBytes("MOUSE $dx $dy\n")
                            outputStream?.flush()
                        } catch (_: Exception) { return@launch }
                    }
                }
                delay(14) // ~70 Hz
            }
        }
    }

    // Lee y descarta los frames que envía el servidor (para no bloquear el TCP)
    private fun drainFrames() {
        receiveJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive && isConnected) {
                    val frameSize = Integer.reverseBytes(inputStream!!.readInt())
                    if (frameSize <= 0 || frameSize > 10_000_000) break
                    var remaining = frameSize
                    while (remaining > 0) {
                        val n = inputStream!!.read(buf, 0, minOf(remaining, buf.size))
                        if (n < 0) break
                        remaining -= n
                    }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { handleDisconnect() }
        }
    }

    private fun handleDisconnect() {
        isConnected = false
        isMoving = false
        hasPrev = false
        smoothDx = 0f
        smoothDy = 0f
        accumDx.set(0)
        accumDy.set(0)
        mouseSenderJob?.cancel()
        sensorManager.unregisterListener(this)
        binding.connectionBar.visibility = View.VISIBLE
        binding.connectBtn.isEnabled = true
        binding.connectedLayout.visibility = View.GONE
    }

    // ── Envío de comandos ─────────────────────────────────────────────────────

    private fun sendCommand(cmd: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                try {
                    outputStream?.writeBytes(cmd)
                    outputStream?.flush()
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun sendClick(button: Int) = sendCommand("CLICK $button\n")
    private fun sendKeyPress(text: String) = sendCommand("KEY $text\n")

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (isConnected) {
            gravitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        receiveJob?.cancel()
        pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
        lifecycleScope.launch(Dispatchers.IO) {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
