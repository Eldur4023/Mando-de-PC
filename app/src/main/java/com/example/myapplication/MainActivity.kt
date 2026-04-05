package com.example.myapplication

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import android.view.View
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var receiveJob: Job? = null
    private var isConnected = false
    private val sendMutex = Mutex()

    // Estado del botón Mover
    private var isMoving = false

    // Acumuladores thread-safe para movimiento del touchpad
    private val accumDx = AtomicInteger(0)
    private val accumDy = AtomicInteger(0)
    // Restos sub-píxel (hilo principal)
    private var touchRemDx = 0f
    private var touchRemDy = 0f
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
    private var touchpadSensitivity = 3.5f

    // Teclado en tiempo real
    private var sentText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    private fun setupUI() {
        binding.connectBtn.setOnClickListener {
            val ip = binding.ipInput.text.toString()
            if (ip.isNotEmpty()) connectToServer(ip)
            else Toast.makeText(this, "Introduce una IP válida", Toast.LENGTH_SHORT).show()
        }

        // ── Botón MOVER ──────────────────────────────────────────────────────
        var moveBtnPressTime = 0L
        var moveBtnStartX = 0f
        var moveBtnStartY = 0f
        var moveBtnLastX = 0f
        var moveBtnLastY = 0f
        var moveBtnDragged = false
        val TAP_MAX_MS = 200L
        val TAP_MOVE_THRESHOLD = 12f
        binding.calibrateBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isMoving = true
                    moveBtnPressTime = System.currentTimeMillis()
                    moveBtnStartX = event.x
                    moveBtnStartY = event.y
                    moveBtnLastX = event.x
                    moveBtnLastY = event.y
                    moveBtnDragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - moveBtnLastX
                    val dy = event.y - moveBtnLastY
                    val totalMoved = abs(event.x - moveBtnStartX) + abs(event.y - moveBtnStartY)
                    if (totalMoved > TAP_MOVE_THRESHOLD) moveBtnDragged = true
                    if (moveBtnDragged) {
                        val fx = dx * touchpadSensitivity + touchRemDx
                        val fy = dy * touchpadSensitivity + touchRemDy
                        val ix = fx.toInt(); val iy = fy.toInt()
                        accumDx.addAndGet(ix); accumDy.addAndGet(iy)
                        touchRemDx = fx - ix;  touchRemDy = fy - iy
                    }
                    moveBtnLastX = event.x
                    moveBtnLastY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isMoving = false
                    if (!moveBtnDragged && System.currentTimeMillis() - moveBtnPressTime < TAP_MAX_MS) {
                        sendClick(1)
                    }
                }
            }
            true
        }

        // ── Teclado en tiempo real ───────────────────────────────────────────
        binding.keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                if (!isConnected) return
                val newText = editable?.toString() ?: ""
                if (newText.length > sentText.length) {
                    val added = newText.substring(sentText.length)
                    for (c in added) {
                        when (c) {
                            ' '  -> sendCommand("KEY space\n")
                            '\n' -> sendCommand("KEY Return\n")
                            else -> sendCommand("KEY $c\n")
                        }
                    }
                } else if (newText.length < sentText.length) {
                    repeat(sentText.length - newText.length) {
                        sendCommand("KEY BackSpace\n")
                    }
                }
                sentText = newText
            }
        })

        // ── Área táctil (tap = click izq, doble tap = click der) ────────────
        binding.touchArea.setOnTouchListener { _, event ->
            if (!isConnected) return@setOnTouchListener false
            handleTap(event)
            true
        }

        // ── Engranaje de configuración ────────────────────────────────────────
        binding.settingsBtn.setOnClickListener {
            showSettingsSheet()
        }
    }

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        sheet.setContentView(view)

        val touchpadSlider = view.findViewById<Slider>(R.id.touchpadSlider)
        val touchpadValueTv = view.findViewById<TextView>(R.id.touchpadValue)

        touchpadSlider.value = touchpadSensitivity.coerceIn(0.5f, 10.0f)
        touchpadValueTv.text = String.format("%.1f", touchpadSensitivity)

        touchpadSlider.addOnChangeListener { _, value, _ ->
            touchpadSensitivity = value
            touchpadValueTv.text = String.format("%.1f", value)
        }

        sheet.show()
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

                if (isMoving && touchMoved) {
                    val fx = dx * touchpadSensitivity + touchRemDx
                    val fy = dy * touchpadSensitivity + touchRemDy
                    val ix = fx.toInt(); val iy = fy.toInt()
                    accumDx.addAndGet(ix); accumDy.addAndGet(iy)
                    touchRemDx = fx - ix;  touchRemDy = fy - iy
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

    // ── Conexión ──────────────────────────────────────────────────────────────

    private fun connectToServer(ip: String) {
        binding.connectBtn.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                socket = Socket(ip, 5555).also { it.tcpNoDelay = true }
                inputStream = DataInputStream(socket!!.getInputStream())
                outputStream = DataOutputStream(socket!!.getOutputStream())

                inputStream!!.readInt()
                inputStream!!.readInt()

                withContext(Dispatchers.Main) {
                    isConnected = true
                    binding.connectionBar.visibility = View.GONE
                    binding.connectedLayout.visibility = View.VISIBLE
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
                delay(8) // ~120 Hz
            }
        }
    }

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
        accumDx.set(0)
        accumDy.set(0)
        touchRemDx = 0f
        touchRemDy = 0f
        sentText = ""
        mouseSenderJob?.cancel()
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

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

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
