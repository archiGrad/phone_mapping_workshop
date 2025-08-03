package com.example.ssid_viz

import java.util.Locale
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaRecorder.AudioSource
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoCdma
import android.telephony.CellInfoNr
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlin.system.exitProcess

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val capabilities: String
)

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int?,
    val deviceClass: String,
    val bondState: String
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)

data class OrientationData(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
)

data class CellularInfo(
    val cellId: String,
    val lac: String,
    val mcc: String,
    val mnc: String,
    val signalStrength: Int,
    val networkType: String,
    val carrierName: String
)

data class SatelliteInfo(
    val satelliteCount: Int,
    val satellitesUsed: Int,
    val usedSatellites: List<SatelliteDetails>
)

data class SatelliteDetails(
    val id: Int,
    val name: String,
    val signalStrength: Float,
    val elevation: Float,
    val azimuth: Float,
    val constellation: String,
    val altitude: Int,
    val speed: Float
)

data class EnvironmentalData(
    val ambientLux: Float?,
    val batteryPercent: Int,
    val bluetoothDevices: List<BluetoothDeviceInfo>,
    val decibelLevel: Float?,
    val cellularInfo: CellularInfo?,
    val satelliteInfo: SatelliteInfo?
)

data class SensorReading(
    val timestamp: String,
    val utcTime: String,
    val location: LocationData?,
    val orientation: OrientationData?,
    val environmental: EnvironmentalData,
    val wifiNetworks: List<WifiNetwork>
)

data class PhotoMetadata(
    val photoFilename: String?,
    val audioFilename: String?,
    val timestamp: String,
    val utcTime: String,
    val location: LocationData?,
    val orientation: OrientationData?,
    val environmental: EnvironmentalData
)

class MainActivity : ComponentActivity(), LocationListener, SensorEventListener {
    private var wifiNetworks by mutableStateOf<List<WifiNetwork>>(emptyList())
    private var currentLocation by mutableStateOf<LocationData?>(null)
    private var orientation by mutableStateOf<OrientationData?>(null)
    private var environmental by mutableStateOf<EnvironmentalData?>(null)
    private var currentTime by mutableStateOf("")
    private var statusMessage by mutableStateOf("Requesting permissions...")
    private var showCamera by mutableStateOf(false)
    private var showAudioRecorder by mutableStateOf(false)
    private var isRecordingAudio by mutableStateOf(false)

    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var telephonyManager: TelephonyManager

    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var lightSensor: Sensor? = null
    private var imageCapture: ImageCapture? = null
    private var mediaRecorder: MediaRecorder? = null
    private val gson = Gson()

    private var sessionDate = ""
    private var currentLux by mutableStateOf<Float?>(null)
    private var currentDecibels by mutableStateOf<Float?>(null)
    private var bluetoothDevices by mutableStateOf<List<BluetoothDeviceInfo>>(emptyList())
    private var cellularInfo by mutableStateOf<CellularInfo?>(null)
    private var satelliteInfo by mutableStateOf<SatelliteInfo?>(null)

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = mutableMapOf<String, BluetoothDeviceInfo>()

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var currentFileStartTime = 0L
    private var currentFileNumber = 1


    private fun getSatelliteName(svid: Int, constellation: String): String {
        return when (constellation) {
            "GPS" -> "GPS-$svid"
            "GLONASS" -> "GLONASS-$svid"
            "Galileo" -> "Galileo-E$svid"
            "BeiDou" -> "BeiDou-C$svid"
            "QZSS" -> "QZSS-$svid"
            "SBAS" -> "SBAS-$svid"
            "IRNSS" -> "IRNSS-$svid"
            else -> "Unknown-$svid"
        }
    }

    private fun getSatelliteAltitude(constellation: String): Int {
        return when (constellation) {
            "GPS" -> 20200
            "GLONASS" -> 19100
            "Galileo" -> 23200
            "BeiDou" -> 21500
            "QZSS" -> 32600
            "SBAS" -> 35786
            "IRNSS" -> 35786
            else -> 20000
        }
    }

    private fun getSatelliteSpeed(constellation: String): Float {
        return when (constellation) {
            "GPS" -> 3.87f
            "GLONASS" -> 3.64f
            "Galileo" -> 3.60f
            "BeiDou" -> 3.07f
            "QZSS" -> 1.59f
            "SBAS" -> 3.07f
            "IRNSS" -> 3.07f
            else -> 3.5f
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val usedSatellites = mutableListOf<SatelliteDetails>()
            var totalUsed = 0

            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    val constellation = when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS"
                        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
                        else -> "Unknown"
                    }

                    usedSatellites.add(SatelliteDetails(
                        id = status.getSvid(i),
                        name = getSatelliteName(status.getSvid(i), constellation),
                        signalStrength = status.getCn0DbHz(i),
                        elevation = status.getElevationDegrees(i),
                        azimuth = status.getAzimuthDegrees(i),
                        constellation = constellation,
                        altitude = getSatelliteAltitude(constellation),
                        speed = getSatelliteSpeed(constellation)
                    ))
                    totalUsed++
                }
            }

            satelliteInfo = SatelliteInfo(
                satelliteCount = status.satelliteCount,
                satellitesUsed = totalUsed,
                usedSatellites = usedSatellites.sortedByDescending { it.signalStrength }
            )
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    device?.let {
                        val deviceInfo = BluetoothDeviceInfo(
                            name = it.name ?: "Unknown Device",
                            address = it.address,
                            rssi = if (rssi != Short.MIN_VALUE.toInt()) rssi else null,
                            deviceClass = it.bluetoothClass?.deviceClass?.toString() ?: "Unknown",
                            bondState = when(it.bondState) {
                                BluetoothDevice.BOND_BONDED -> "Paired"
                                BluetoothDevice.BOND_BONDING -> "Pairing"
                                else -> "Unpaired"
                            }
                        )
                        discoveredDevices[it.address] = deviceInfo
                        bluetoothDevices = discoveredDevices.values.toList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Discovery finished, will restart in 5 seconds
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            initializeServices()
        } else {
            statusMessage = "Some permissions denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        bluetoothAdapter = bluetoothManager.adapter

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(16.dp)
            ) {
                when {
                    showCamera -> CameraScreen()
                    showAudioRecorder -> AudioRecorderScreen()
                    else -> MainScreen()
                }
            }
        }

        checkPermissions()
        startTimeUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(this)
            try {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (e: Exception) {
                // Ignore
            }
        }
        sensorManager.unregisterListener(this)
        stopAudioRecording()
        stopMediaRecorder()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        bluetoothAdapter?.cancelDiscovery()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            initializeServices()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun initializeServices() {
        statusMessage = "Starting..."
        startLocationUpdates()
        startDataCollection()
        startSensorUpdates()
        startAudioRecording()
        startBluetoothScanning()
        startCellularMonitoring()
        startSatelliteMonitoring()
    }

    private fun getNextFileNumber(prefix: String): Int {
        val dataDir = File(getExternalFilesDir(null), "SSID_Data")
        if (!dataDir.exists()) return 1

        val files = dataDir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".json") }
        return if (files.isNullOrEmpty()) {
            1
        } else {
            val numbers = files.mapNotNull { file ->
                val nameWithoutExtension = file.nameWithoutExtension
                val numberPart = nameWithoutExtension.substringAfterLast("_")
                numberPart.toIntOrNull()
            }
            (numbers.maxOrNull() ?: 0) + 1
        }
    }

    private fun startCellularMonitoring() {
        lifecycleScope.launch {
            while (true) {
                updateCellularInfo()
                delay(5000)
            }
        }
    }

    private fun startSatelliteMonitoring() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
            }
        } catch (e: Exception) {
            statusMessage = "Satellite monitoring failed: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun updateCellularInfo() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {

                val networkOperator = telephonyManager.networkOperator
                val mcc = if (networkOperator.length >= 3) networkOperator.substring(0, 3) else "Unknown"
                val mnc = if (networkOperator.length >= 5) networkOperator.substring(3) else "Unknown"
                val carrierName = telephonyManager.networkOperatorName ?: "Unknown"
                val networkType = getNetworkTypeString(telephonyManager.networkType)

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    val allCellInfo = telephonyManager.allCellInfo
                    if (!allCellInfo.isNullOrEmpty()) {
                        val cellInfo = allCellInfo[0]
                        val (cellId, lac, signalStrength) = parseCellInfo(cellInfo)

                        this.cellularInfo = CellularInfo(
                            cellId = cellId,
                            lac = lac,
                            mcc = mcc,
                            mnc = mnc,
                            signalStrength = signalStrength,
                            networkType = networkType,
                            carrierName = carrierName
                        )
                    }
                }
            }
        } catch (e: Exception) {
            statusMessage = "Cellular info failed: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun parseCellInfo(cellInfo: CellInfo): Triple<String, String, Int> {
        return when (cellInfo) {
            is CellInfoGsm -> {
                val identity = cellInfo.cellIdentity
                val signalStrength = cellInfo.cellSignalStrength
                Triple(
                    identity.cid.toString(),
                    identity.lac.toString(),
                    signalStrength.dbm
                )
            }
            is CellInfoLte -> {
                val identity = cellInfo.cellIdentity
                val signalStrength = cellInfo.cellSignalStrength
                Triple(
                    identity.ci.toString(),
                    identity.tac.toString(),
                    signalStrength.dbm
                )
            }
            is CellInfoWcdma -> {
                val identity = cellInfo.cellIdentity
                val signalStrength = cellInfo.cellSignalStrength
                Triple(
                    identity.cid.toString(),
                    identity.lac.toString(),
                    signalStrength.dbm
                )
            }
            is CellInfoCdma -> {
                val identity = cellInfo.cellIdentity
                val signalStrength = cellInfo.cellSignalStrength
                Triple(
                    identity.basestationId.toString(),
                    identity.networkId.toString(),
                    signalStrength.dbm
                )
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr) {
                    val identity = cellInfo.cellIdentity as android.telephony.CellIdentityNr
                    val signalStrength = cellInfo.cellSignalStrength
                    Triple(
                        identity.nci.toString(),
                        identity.tac.toString(),
                        signalStrength.dbm
                    )
                } else {
                    Triple("Unknown", "Unknown", 0)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getNetworkTypeString(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "3G HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            else -> "Unknown"
        }
    }

    private fun getChannelFromFrequency(freq: Int): Int {
        return when {
            freq in 2412..2484 -> (freq - 2412) / 5 + 1
            freq in 5170..5825 -> (freq - 5000) / 5
            else -> 0
        }
    }

    private fun startBluetoothScanning() {
        lifecycleScope.launch {
            while (true) {
                scanBluetoothDevices()
                delay(5000)
            }
        }
    }

    private fun scanBluetoothDevices() {
        try {
            val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            }

            if (hasBluetoothPermission && bluetoothAdapter != null) {
                if (bluetoothAdapter!!.isDiscovering) {
                    bluetoothAdapter!!.cancelDiscovery()
                }
                bluetoothAdapter!!.startDiscovery()
            }
        } catch (e: Exception) {
            statusMessage = "Bluetooth scan failed: ${e.message}"
        }
    }

    private fun startAudioRecording() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {

                val bufferSize = AudioRecord.getMinBufferSize(
                    44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                isRecording = true
                audioRecord?.startRecording()

                lifecycleScope.launch {
                    val buffer = ShortArray(bufferSize)
                    while (isRecording) {
                        try {
                            val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                            if (read > 0) {
                                val amplitude = buffer.maxOrNull()?.toDouble() ?: 0.0
                                currentDecibels = if (amplitude > 0) {
                                    (20 * log10(amplitude / 32767.0)).toFloat()
                                } else {
                                    -90f
                                }
                            }
                        } catch (e: Exception) {
                            currentDecibels = null
                        }
                        delay(100)
                    }
                }
            }
        } catch (e: Exception) {
            statusMessage = "Audio recording failed: ${e.message}"
        }
    }

    private fun stopAudioRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startMediaRecorder() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {

                val audioFilename = "sound_${System.currentTimeMillis()}.aac"
                val audioDir = File(getExternalFilesDir(null), "SSID_Audio")
                if (!audioDir.exists()) audioDir.mkdirs()

                val audioFile = File(audioDir, audioFilename)

                mediaRecorder = MediaRecorder().apply {
                    setAudioSource(AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(audioFile.absolutePath)
                    prepare()
                    start()
                }

                isRecordingAudio = true
                statusMessage = "Recording audio..."
            }
        } catch (e: Exception) {
            statusMessage = "Audio recording failed: ${e.message}"
            isRecordingAudio = false
        }
    }


    private fun stopMediaRecorder() {
        try {
            if (isRecordingAudio && mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.release()

                val audioFilename = "sound_${System.currentTimeMillis()}.aac"
                saveAudioMetadata(audioFilename)
                statusMessage = "Audio + metadata saved"

                mediaRecorder = null
                isRecordingAudio = false
                showAudioRecorder = false
            }
        } catch (e: Exception) {
            statusMessage = "Audio save failed: ${e.message}"
            isRecordingAudio = false
        }
    }

    private fun startTimeUpdates() {
        lifecycleScope.launch {
            while (true) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                currentTime = sdf.format(Date())
                delay(1000)
            }
        }
    }

    private fun startDataCollection() {
        lifecycleScope.launch {
            while (true) {
                updateWifiNetworks()
                updateEnvironmentalData()
                saveDataSnapshot()
                delay(5000)
            }
        }
    }

    private fun updateEnvironmentalData() {
        try {
            val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            environmental = EnvironmentalData(
                ambientLux = currentLux,
                batteryPercent = batteryPercent,
                bluetoothDevices = bluetoothDevices,
                decibelLevel = currentDecibels,
                cellularInfo = cellularInfo,
                satelliteInfo = satelliteInfo
            )
        } catch (e: Exception) {
            statusMessage = "Environmental data failed: ${e.message}"
        }
    }

    // Update getNextFileNumber function
    private fun getCurrentFileNumber(): Int {
        val currentTime = System.currentTimeMillis()

        // If more than 10 minutes (600,000 ms) have passed, start a new file
        if (currentFileStartTime == 0L || (currentTime - currentFileStartTime) > 600000) {
            currentFileStartTime = currentTime
            val dataDir = File(getExternalFilesDir(null), "SSID_Data")
            if (!dataDir.exists()) return 1

            val files = dataDir.listFiles { _, name ->
                name.startsWith("sensor_data_$sessionDate") && name.endsWith(".json")
            }
            currentFileNumber = if (files.isNullOrEmpty()) {
                1
            } else {
                val numbers = files.mapNotNull { file ->
                    val nameWithoutExtension = file.nameWithoutExtension
                    val numberPart = nameWithoutExtension.substringAfterLast("_")
                    numberPart.toIntOrNull()
                }
                (numbers.maxOrNull() ?: 0) + 1
            }
        }

        return currentFileNumber
    }


    private fun saveDataSnapshot() {
        try {
            val reading = SensorReading(
                timestamp = System.currentTimeMillis().toString(),
                utcTime = currentTime,
                location = currentLocation,
                orientation = orientation,
                environmental = environmental ?: EnvironmentalData(null, 0, emptyList(), null, null, null),
                wifiNetworks = wifiNetworks
            )

            val dataDir = File(getExternalFilesDir(null), "SSID_Data")
            if (!dataDir.exists()) dataDir.mkdirs()

            val fileNumber = getCurrentFileNumber()
            val sensorFile = File(dataDir, "sensor_data_${sessionDate}_$fileNumber.json")

            val jsonObject = if (sensorFile.exists()) {
                JsonParser.parseString(sensorFile.readText()).asJsonObject
            } else {
                JsonObject().apply { add("readings", JsonArray()) }
            }

            val readingsArray = jsonObject.getAsJsonArray("readings")
            val newReading = JsonParser.parseString(gson.toJson(reading))
            readingsArray.add(newReading)

            sensorFile.writeText(gson.toJson(jsonObject))
            statusMessage = "Data saved: ${wifiNetworks.size} WiFi, ${bluetoothDevices.size} BT, ${environmental?.batteryPercent ?: 0}% battery"

        } catch (e: Exception) {
            statusMessage = "Save failed: ${e.message}"
        }
    }

    private fun savePhotoMetadata(photoFilename: String) {
        try {
            val photoData = PhotoMetadata(
                photoFilename = photoFilename,
                audioFilename = null,
                timestamp = System.currentTimeMillis().toString(),
                utcTime = currentTime,
                location = currentLocation,
                orientation = orientation,
                environmental = environmental ?: EnvironmentalData(null, 0, emptyList(), null, null, null)
            )

            val dataDir = File(getExternalFilesDir(null), "SSID_Data")
            if (!dataDir.exists()) dataDir.mkdirs()

            val fileNumber = getNextFileNumber("images_$sessionDate")
            val imagesFile = File(dataDir, "images_${sessionDate}_$fileNumber.json")

            val jsonObject = JsonObject().apply { add("photos", JsonArray()) }
            val photosArray = jsonObject.getAsJsonArray("photos")
            val newPhoto = JsonParser.parseString(gson.toJson(photoData))
            photosArray.add(newPhoto)

            imagesFile.writeText(gson.toJson(jsonObject))

        } catch (e: Exception) {
            statusMessage = "Photo metadata save failed: ${e.message}"
        }
    }

    private fun saveAudioMetadata(audioFilename: String) {
        try {
            val audioData = PhotoMetadata(
                photoFilename = null,
                audioFilename = audioFilename,
                timestamp = System.currentTimeMillis().toString(),
                utcTime = currentTime,
                location = currentLocation,
                orientation = orientation,
                environmental = environmental ?: EnvironmentalData(null, 0, emptyList(), null, null, null)
            )

            val dataDir = File(getExternalFilesDir(null), "SSID_Data")
            if (!dataDir.exists()) dataDir.mkdirs()

            val fileNumber = getNextFileNumber("images_$sessionDate")
            val imagesFile = File(dataDir, "images_${sessionDate}_$fileNumber.json")

            val jsonObject = JsonObject().apply { add("photos", JsonArray()) }
            val photosArray = jsonObject.getAsJsonArray("photos")
            val newAudio = JsonParser.parseString(gson.toJson(audioData))
            photosArray.add(newAudio)

            imagesFile.writeText(gson.toJson(jsonObject))

        } catch (e: Exception) {
            statusMessage = "Audio metadata save failed: ${e.message}"
        }
    }

    private fun startSensorUpdates() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    1f,
                    this
                )

                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    1f,
                    this
                )
            }
        } catch (e: Exception) {
            statusMessage = "Location setup failed: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun updateWifiNetworks() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

                wifiManager.startScan()
                val scanResults = wifiManager.scanResults
                wifiNetworks = scanResults.map { result ->
                    WifiNetwork(
                        ssid = if (result.SSID.isNotEmpty()) result.SSID else "Hidden",
                        bssid = result.BSSID ?: "Unknown",
                        rssi = result.level,
                        frequency = result.frequency,
                        channel = getChannelFromFrequency(result.frequency),
                        capabilities = result.capabilities ?: ""
                    )
                }.sortedByDescending { it.rssi }
            }
        } catch (e: Exception) {
            statusMessage = "WiFi scan failed"
        }
    }

    @Suppress("DEPRECATION")
    override fun onLocationChanged(location: Location) {
        currentLocation = LocationData(location.latitude, location.longitude, location.accuracy)
    }

    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    @Suppress("DEPRECATION")
    override fun onProviderEnabled(provider: String) {}
    @Suppress("DEPRECATION")
    override fun onProviderDisabled(provider: String) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            }
            Sensor.TYPE_LIGHT -> {
                currentLux = event.values[0]
            }
        }
        updateOrientation()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientation() {
        if (accelerometerReading.isNotEmpty() && magnetometerReading.isNotEmpty()) {
            SensorManager.getRotationMatrix(
                rotationMatrix, null, accelerometerReading, magnetometerReading
            )
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            orientation = OrientationData(
                azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat(),
                pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
                roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            )
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFilename = "photo_${System.currentTimeMillis()}.jpg"

        val photoDir = File(getExternalFilesDir(null), "SSID_Photos")
        if (!photoDir.exists()) photoDir.mkdirs()

        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
            File(photoDir, photoFilename)
        ).build()

        imageCapture.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    savePhotoMetadata(photoFilename)
                    statusMessage = "Photo + metadata saved"
                    showCamera = false
                }
                override fun onError(exception: ImageCaptureException) {
                    statusMessage = "Photo failed: ${exception.message}"
                }
            }
        )
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finishAffinity()
    }

    private fun exitApp() {
        finishAffinity()
        exitProcess(0)
    }

    @Composable
    private fun MainScreen() {
        LazyColumn {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { showCamera = true },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Photo", color = Color.Black, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showAudioRecorder = true },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                    ) {
                        Text("Audio", color = Color.Black, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { restartApp() },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                    ) {
                        Text("Restart", color = Color.Black, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { exitApp() },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)
                    ) {
                        Text("Exit", color = Color.Black, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text(
                    text = "UTC: $currentTime",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                Text(
                    text = statusMessage,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                currentLocation?.let { location ->
                    Text(
                        text = "GPS: ${String.format(Locale.getDefault(), "%.6f", location.latitude)}, ${String.format(Locale.getDefault(), "%.6f", location.longitude)}",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Accuracy: ${location.accuracy}m",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } ?: Text(
                    text = "GPS: No location",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                orientation?.let { orient ->
                    Text(
                        text = "Azimuth: ${String.format(Locale.getDefault(), "%.1f", orient.azimuth)}°",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Pitch: ${String.format(Locale.getDefault(), "%.1f", orient.pitch)}°",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Roll: ${String.format(Locale.getDefault(), "%.1f", orient.roll)}°",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } ?: Text(
                    text = "Orientation: No data",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                Text(
                    text = "Light: ${currentLux?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "N/A"} lux",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Sound: ${currentDecibels?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "N/A"} dB",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Battery: ${environmental?.batteryPercent ?: 0}%",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                cellularInfo?.let { cell ->
                    Text(
                        text = "Cell: ${cell.carrierName} ${cell.networkType}",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Tower: ${cell.cellId} | ${cell.signalStrength}dBm",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "MCC/MNC: ${cell.mcc}/${cell.mnc}",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } ?: Text(
                    text = "Cellular: No data",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                satelliteInfo?.let { sat ->
                    if (sat.usedSatellites.isNotEmpty()) {
                        Text(
                            text = "Satellites used (${sat.satellitesUsed}):",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    } else {
                        Text(
                            text = "No satellites used",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                } ?: Text(
                    text = "Satellites: No data",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            satelliteInfo?.usedSatellites?.let { satellites ->
                items(satellites) { satellite ->
                    Text(
                        text = "• ${satellite.name} | ${String.format(Locale.getDefault(), "%.1f", satellite.signalStrength)} dB-Hz | Alt: ${satellite.altitude}km",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                if (bluetoothDevices.isNotEmpty()) {
                    Text(
                        text = "Bluetooth devices (${bluetoothDevices.size}):",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                } else {
                    Text(
                        text = "No Bluetooth devices found",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            items(bluetoothDevices) { device ->
                Text(
                    text = "• ${device.name} | ${device.rssi?.let { "${it}dBm" } ?: "No RSSI"} | ${device.bondState}",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                if (wifiNetworks.isNotEmpty()) {
                    Text(
                        text = "WiFi networks (${wifiNetworks.size}):",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                } else {
                    Text(
                        text = "No WiFi networks found",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            items(wifiNetworks) { network ->
                Text(
                    text = "• ${network.ssid} | ${network.rssi}dBm | Ch${network.channel}",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun CameraScreen() {
        val context = LocalContext.current
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        Column {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val executor = ContextCompat.getMainExecutor(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        imageCapture = ImageCapture.Builder().build()

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, cameraSelector, preview, imageCapture
                            )
                        } catch (exc: Exception) {
                            statusMessage = "Camera failed"
                        }
                    }, executor)
                    previewView
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { takePhoto() },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                ) {
                    Text("Capture", color = Color.Black, fontSize = 14.sp)
                }
                Button(
                    onClick = { showCamera = false },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Back", color = Color.Black, fontSize = 14.sp)
                }
            }
        }
    }

    @Composable
    private fun AudioRecorderScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isRecordingAudio) "Recording Audio..." else "Audio Recorder",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        if (isRecordingAudio) {
                            stopMediaRecorder()
                        } else {
                            startMediaRecorder()
                        }
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingAudio) Color.Red else Color.Green
                    )
                ) {
                    Text(
                        text = if (isRecordingAudio) "Stop" else "Record",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                Button(
                    onClick = {
                        if (isRecordingAudio) {
                            stopMediaRecorder()
                        }
                        showAudioRecorder = false
                    },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                ) {
                    Text("Back", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }

}
