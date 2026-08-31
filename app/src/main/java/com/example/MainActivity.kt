package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var bassSeekBar: SeekBar
    private lateinit var virtualizerSeekBar: SeekBar
    private lateinit var loudnessSeekBar: SeekBar
    private lateinit var presetSpinner: Spinner
    private lateinit var btnSaveCustom: Button
    private lateinit var eqBandsContainer: LinearLayout
    
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryProgress: ProgressBar
    private lateinit var batteryText: TextView

    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // Названия пресетов. 0-3 это стандартные (если поддерживаются), последний — пользовательский.
    private val presets = mutableListOf("Flat", "Rock", "Pop", "Jazz", "Пользовательский")

    // Переменные для дебаунса (ограничения частоты обновлений), чтобы не было "щелчков" при движении
    private var lastBassUpdate = 0L
    private var lastVirtualizerUpdate = 0L
    private var lastLoudnessUpdate = 0L
    private var lastEqUpdate = 0L

    // Ресивер для прослушивания заряда батареи Bluetooth-устройства
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            // Скрытое системное действие для отслеживания заряда батареи
            if (action == "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED") {
                val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                if (level in 0..100) {
                    updateBatteryUI(level)
                }
            } else if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                // При подключении запрашиваем уровень через рефлексию
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let { getBatteryLevelWithReflection(it) }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.MODIFY_AUDIO_SETTINGS] ?: true
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        } else {
            true
        }

        if (audioGranted && btGranted) {
            initAudioEffects()
            registerBatteryReceiver()
            checkCurrentDeviceBattery()
        } else {
            Toast.makeText(this, "Требуются разрешения для работы приложения", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bassSeekBar = findViewById(R.id.bassSeekBar)
        virtualizerSeekBar = findViewById(R.id.virtualizerSeekBar)
        loudnessSeekBar = findViewById(R.id.loudnessSeekBar)
        presetSpinner = findViewById(R.id.presetSpinner)
        btnSaveCustom = findViewById(R.id.btnSaveCustom)
        eqBandsContainer = findViewById(R.id.eqBandsContainer)
        
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryProgress = findViewById(R.id.batteryProgress)
        batteryText = findViewById(R.id.batteryText)

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        
        // В Android 12+ (API 31) требуется разрешение BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        } else {
            initAudioEffects()
            registerBatteryReceiver()
            checkCurrentDeviceBattery()
        }
    }

    private fun initAudioEffects() {
        try {
            // Инициализация для глобального аудиомикса (session 0).
            // Применяется ко всем звукам устройства. Работает не на всех прошивках новых Android.
            bassBoost = BassBoost(0, 0)
            equalizer = Equalizer(0, 0)
            virtualizer = Virtualizer(0, 0)
            loudnessEnhancer = LoudnessEnhancer(0)

            bassBoost?.enabled = true
            equalizer?.enabled = true
            virtualizer?.enabled = true
            loudnessEnhancer?.enabled = true

            setupBassBoost()
            setupVirtualizer()
            setupLoudnessEnhancer()
            setupEqualizer()
            setupPresets()

        } catch (e: Exception) {
            Log.e("AudioEffects", "Ошибка инициализации", e)
            Toast.makeText(this, "Не удалось инициализировать аудиоэффекты", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBassBoost() {
        val maxStrength = 1000
        bassSeekBar.max = maxStrength
        
        // Загрузка сохраненного значения
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)
        val savedBass = prefs.getInt("bassLevel", 0)
        bassSeekBar.progress = savedBass
        
        try {
            bassBoost?.setStrength(savedBass.toShort())
        } catch (e: Exception) {}

        bassSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val now = System.currentTimeMillis()
                    if (now - lastBassUpdate > 150) {
                        try { bassBoost?.setStrength(progress.toShort()) } catch (e: Exception) {}
                        lastBassUpdate = now
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Сохранение уровня баса при отпускании ползунка и финальное применение эффекта
                val finalProgress = seekBar?.progress ?: 0
                prefs.edit().putInt("bassLevel", finalProgress).apply()
                try { bassBoost?.setStrength(finalProgress.toShort()) } catch (e: Exception) {}
            }
        })
    }

    private fun setupVirtualizer() {
        virtualizerSeekBar.max = 1000
        
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)
        val savedVirtualizer = prefs.getInt("virtualizerLevel", 0)
        virtualizerSeekBar.progress = savedVirtualizer
        
        try {
            virtualizer?.setStrength(savedVirtualizer.toShort())
        } catch (e: Exception) {}

        virtualizerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val now = System.currentTimeMillis()
                    if (now - lastVirtualizerUpdate > 150) {
                        try { virtualizer?.setStrength(progress.toShort()) } catch (e: Exception) {}
                        lastVirtualizerUpdate = now
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalProgress = seekBar?.progress ?: 0
                prefs.edit().putInt("virtualizerLevel", finalProgress).apply()
                try { virtualizer?.setStrength(finalProgress.toShort()) } catch (e: Exception) {}
            }
        })
    }

    private fun setupLoudnessEnhancer() {
        loudnessSeekBar.max = 10000 // mB (millibels)
        
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)
        val savedLoudness = prefs.getInt("loudnessLevel", 0)
        loudnessSeekBar.progress = savedLoudness
        
        try {
            loudnessEnhancer?.setTargetGain(savedLoudness)
        } catch (e: Exception) {}

        loudnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val now = System.currentTimeMillis()
                    if (now - lastLoudnessUpdate > 150) {
                        try { loudnessEnhancer?.setTargetGain(progress) } catch (e: Exception) {}
                        lastLoudnessUpdate = now
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalProgress = seekBar?.progress ?: 0
                prefs.edit().putInt("loudnessLevel", finalProgress).apply()
                try { loudnessEnhancer?.setTargetGain(finalProgress) } catch (e: Exception) {}
            }
        })
    }

    private fun setupEqualizer() {
        eqBandsContainer.removeAllViews()
        val eq = equalizer ?: return

        val numBands = eq.numberOfBands
        val minEQLevel = eq.bandLevelRange[0]
        val maxEQLevel = eq.bandLevelRange[1]
        
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)

        for (i in 0 until numBands) {
            val bandIndex = i.toShort()
            
            // Восстановление сохраненного значения для ползунков
            val savedLevel = prefs.getInt("band_$bandIndex", eq.getBandLevel(bandIndex).toInt()).toShort()
            eq.setBandLevel(bandIndex, savedLevel)

            // Форматирование частоты и добавление подписей
            val freq = eq.getCenterFreq(bandIndex)
            val hz = freq / 1000
            val freqText = when {
                hz < 250 -> "$hz Hz\n(Бас)"
                hz < 4000 -> if (hz >= 1000) "${hz/1000} kHz\n(СЧ)" else "$hz Hz\n(СЧ)"
                else -> "${hz / 1000} kHz\n(ВЧ)"
            }

            val bandView = LayoutInflater.from(this).inflate(R.layout.item_eq_band, eqBandsContainer, false)
            val bandFreqText: TextView = bandView.findViewById(R.id.bandFreqText)
            val bandSeekBar: SeekBar = bandView.findViewById(R.id.bandSeekBar)
            val bandGainText: TextView = bandView.findViewById(R.id.bandGainText)

            bandFreqText.text = freqText
            
            // Для SeekBar значение должно начинаться с 0
            bandSeekBar.max = maxEQLevel - minEQLevel
            bandSeekBar.progress = savedLevel - minEQLevel
            
            bandGainText.text = "${savedLevel / 100} dB"

            bandSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val newLevel = (progress + minEQLevel).toShort()
                    bandGainText.text = "${newLevel / 100} dB"
                    
                    if (fromUser) {
                        // Переключение на "Пользовательский" при ручном изменении
                        presetSpinner.setSelection(presets.size - 1)
                        
                        // Троттлинг обновлений для предотвращения щелчков
                        val now = System.currentTimeMillis()
                        if (now - lastEqUpdate > 150) {
                            try { eq.setBandLevel(bandIndex, newLevel) } catch (e: Exception) {}
                            lastEqUpdate = now
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    // Окончательное применение эффекта при отпускании
                    val newLevel = ((seekBar?.progress ?: 0) + minEQLevel).toShort()
                    try { eq.setBandLevel(bandIndex, newLevel) } catch (e: Exception) {}
                }
            })

            eqBandsContainer.addView(bandView)
        }
    }

    private fun setupPresets() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter
        
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)
        val lastPreset = prefs.getInt("selectedPreset", 0)
        presetSpinner.setSelection(lastPreset)

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("selectedPreset", position).apply()
                
                if (position < presets.size - 1) {
                    // Применяем предустановленные системные пресеты
                    applyPreset(position)
                } else {
                    // Применяем сохраненный пользовательский
                    loadCustomPreset()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSaveCustom.setOnClickListener {
            saveCustomPreset()
            Toast.makeText(this, "Пользовательские настройки сохранены", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun applyPreset(presetIndex: Int) {
        val eq = equalizer ?: return
        try {
            if (presetIndex < eq.numberOfPresets) {
                eq.usePreset(presetIndex.toShort())
                updateEqUIFromCurrentState()
            }
        } catch (e: Exception) {}
    }

    private fun saveCustomPreset() {
        val eq = equalizer ?: return
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)
        val editor = prefs.edit()
        for (i in 0 until eq.numberOfBands) {
            val bandIndex = i.toShort()
            editor.putInt("band_$bandIndex", eq.getBandLevel(bandIndex).toInt())
        }
        editor.apply()
    }

    private fun loadCustomPreset() {
        val eq = equalizer ?: return
        val prefs = getSharedPreferences("AudioPrefs", MODE_PRIVATE)
        for (i in 0 until eq.numberOfBands) {
            val bandIndex = i.toShort()
            val savedLevel = prefs.getInt("band_$bandIndex", 0).toShort()
            try {
                eq.setBandLevel(bandIndex, savedLevel)
            } catch (e: Exception) {}
        }
        updateEqUIFromCurrentState()
    }

    private fun updateEqUIFromCurrentState() {
        val eq = equalizer ?: return
        val minEQLevel = eq.bandLevelRange[0]
        for (i in 0 until eq.numberOfBands) {
            val bandIndex = i.toShort()
            val level = eq.getBandLevel(bandIndex)
            
            val bandView = eqBandsContainer.getChildAt(i) as? LinearLayout
            val bandSeekBar = bandView?.findViewById<SeekBar>(R.id.bandSeekBar)
            val bandGainText = bandView?.findViewById<TextView>(R.id.bandGainText)
            
            bandSeekBar?.progress = level - minEQLevel
            bandGainText?.text = "${level / 100} dB"
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        try {
            // Флаг RECEIVER_NOT_EXPORTED требуется в Android 14+ для внутренних BroadcastReceiver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(batteryReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("BluetoothBattery", "Ошибка регистрации Receiver", e)
        }
    }

    private fun checkCurrentDeviceBattery() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        
        if (adapter != null && adapter.isEnabled) {
            // Проверка разрешений для Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            // Если устройство подключено, пытаемся получить заряд напрямую через рефлексию
            val connectedDevices = adapter.bondedDevices
            for (device in connectedDevices) {
                getBatteryLevelWithReflection(device)
            }
        }
    }

    private fun getBatteryLevelWithReflection(device: BluetoothDevice) {
        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as Int
            if (level in 0..100) {
                updateBatteryUI(level)
            }
        } catch (e: Exception) {
            Log.d("BluetoothBattery", "Не удалось получить заряд устройства: ${device.name}")
        }
    }

    private fun updateBatteryUI(level: Int) {
        runOnUiThread {
            batteryProgress.progress = level
            batteryText.text = "$level%"
            
            // Динамический цвет: зеленый -> желтый -> красный
            val color = when {
                level > 50 -> android.graphics.Color.parseColor("#4CAF50") // Зеленый
                level > 20 -> android.graphics.Color.parseColor("#FFEB3B") // Желтый
                else -> android.graphics.Color.parseColor("#F44336") // Красный
            }
            batteryProgress.progressTintList = android.content.res.ColorStateList.valueOf(color)
            batteryIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
        bassBoost?.release()
        equalizer?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()
    }
}
