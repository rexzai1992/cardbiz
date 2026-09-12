package com.cardbiz.ecrtest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.cardbiz.ecrtest.protocol.EcrCodec
import com.cardbiz.ecrtest.protocol.EcrCommandInput
import com.cardbiz.ecrtest.protocol.EcrCommandType
import com.cardbiz.ecrtest.protocol.EcrCommands
import com.cardbiz.ecrtest.protocol.DecodedEcrMessage
import com.cardbiz.ecrtest.protocol.PaymentRoute
import com.cardbiz.ecrtest.session.EcrSession
import com.cardbiz.ecrtest.session.EcrTransport
import com.cardbiz.ecrtest.transport.BluetoothRfcommTransport
import com.cardbiz.ecrtest.transport.TcpListenTransport
import com.cardbiz.ecrtest.transport.TcpTransport
import com.cardbiz.ecrtest.transport.UsbSerialTransport
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private enum class ConnectionMode(val label: String) {
        USB("Cable - USB / RS232 (automatic)"),
        BLUETOOTH("Bluetooth"),
        TCP("WiFi - TCP/IP"),
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var activeTask: Future<*>? = null
    @Volatile private var transport: EcrTransport? = null
    @Volatile private var connectionGeneration = 0
    @Volatile private var connectionInProgress = false
    private var connectedUsbDeviceId: Int? = null
    private var pingSucceeded = false
    private var usbAutoPaused = false

    private val bluetoothDevices = mutableListOf<BluetoothDevice>()
    private val usbDevices = mutableListOf<UsbDevice>()

    private lateinit var statusText: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var payAccountInput: EditText
    private lateinit var transactionInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var testModeCheck: CheckBox
    private lateinit var settlementCheck: CheckBox
    private lateinit var pingButton: Button
    private lateinit var payButton: Button
    private lateinit var ewalletButton: Button
    private lateinit var duitnowButton: Button
    private lateinit var lastTransactionButton: Button
    private lateinit var batchButton: Button
    private lateinit var cancelButton: Button
    private lateinit var parsedText: TextView

    private lateinit var advancedPanel: LinearLayout
    private lateinit var modeSpinner: Spinner
    private lateinit var deviceSpinner: Spinner
    private lateinit var deviceContainer: LinearLayout
    private lateinit var bluetoothUuidInput: EditText
    private lateinit var bluetoothUuidContainer: LinearLayout
    private lateinit var tcpHostInput: EditText
    private lateinit var tcpHostContainer: LinearLayout
    private lateinit var tcpPortInput: EditText
    private lateinit var tcpPortContainer: LinearLayout
    private lateinit var cableRadio: RadioButton
    private lateinit var wifiRadio: RadioButton
    private lateinit var wifiPanel: LinearLayout
    private lateinit var wifiListenCheck: CheckBox
    private lateinit var wifiDeviceIpText: TextView
    private var syncingConnectionUi = false
    private lateinit var logText: TextView
    private val logBuffer = StringBuilder()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }
    private val usbManager: UsbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> bluetoothDevice(intent)?.let(::addBluetoothDevice)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> appendLog("Bluetooth discovery finished")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> usbDevice(intent)?.let(::handleUsbAttached)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> usbDevice(intent)?.let(::handleUsbDetached)
                ACTION_USB_PERMISSION -> handleUsbPermission(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        registerDeviceReceiver()
        wireModeSelection()
        appendLog("CardBiz ECR Test started")
        autoDetectUsb()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbDevice(intent)?.let(::handleUsbAttached)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        connectionGeneration++
        activeTask?.cancel(true)
        runCatching { transport?.disconnect() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        logText = text("", 12f, INK).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.WHITE)
            minHeight = dp(200)
        }

        val root = vertical().apply {
            setPadding(dp(16), dp(14), dp(16), dp(28))
            setBackgroundColor(SURFACE)
        }

        root.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(vertical().apply {
                addView(text("CardBiz POS Test", 25f, NAVY).apply { setTypeface(typeface, Typeface.BOLD) })
                addView(text("TEST TERMINAL ONLY", 12f, DANGER).apply { setTypeface(typeface, Typeface.BOLD) })
            }, weighted())
            addView(text("v0.5.0", 12f, Color.DKGRAY))
        }, matchWrap())

        statusCard = vertical().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(NAVY, 12)
            statusText = text("Looking for terminal...", 17f, Color.WHITE).apply {
                setTypeface(typeface, Typeface.BOLD)
            }
            statusDetailText = text("Plug in the USB/OTG cable", 13f, Color.WHITE)
            addView(statusText)
            addView(statusDetailText)
        }
        root.addView(statusCard, margins(top = 14, bottom = 8))

        val content = vertical()
        val prefs = getPreferences(Context.MODE_PRIVATE)

        content.addView(label("CONNECTION"))
        val methodGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        cableRadio = RadioButton(this).apply { text = "CABLE  (USB / RS232)" }
        wifiRadio = RadioButton(this).apply { text = "WIFI  (TCP/IP)" }
        methodGroup.addView(cableRadio, weighted())
        methodGroup.addView(wifiRadio, weighted())
        content.addView(methodGroup, matchWrap())

        tcpHostInput = input(prefs.getString(PREF_WIFI_HOST, "") ?: "").apply {
            hint = "Terminal IP, e.g. 192.168.100.50"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        tcpHostContainer = labeled("TERMINAL IP", tcpHostInput)
        tcpPortInput = input(prefs.getString(PREF_WIFI_PORT, "") ?: "").apply {
            hint = "Port from CardBiz"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
        }
        tcpPortContainer = labeled("PORT", tcpPortInput)
        wifiListenCheck = CheckBox(this).apply {
            text = "Terminal connects to this device instead (app listens on the port)"
            isChecked = prefs.getBoolean(PREF_WIFI_LISTEN, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PREF_WIFI_LISTEN, checked).apply()
                tcpHostContainer.visibility = if (checked) View.GONE else View.VISIBLE
            }
        }
        wifiDeviceIpText = text("", 12f, Color.DKGRAY)
        wifiPanel = vertical().apply {
            background = rounded(Color.WHITE, 10)
            setPadding(dp(12), dp(6), dp(12), dp(12))
            addView(horizontal().apply {
                addView(tcpHostContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
                addView(tcpPortContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            }, matchWrap())
            addView(wifiListenCheck)
            addView(wifiDeviceIpText)
            addView(horizontal().apply {
                addView(primaryButton("CONNECT WIFI") {
                    usbAutoPaused = true
                    connectSelected()
                }.apply { minHeight = dp(46); textSize = 14f }, weighted().apply { rightMargin = dp(4) })
                addView(secondaryButton("DISCONNECT") { disconnectCurrent(userInitiated = true) }, weighted().apply { leftMargin = dp(4) })
            }, margins(top = 6))
            addView(text("v1.59 defines only RS232 and Bluetooth. WiFi works only if CardBiz enabled ECR over TCP/IP on the terminal and supplied the IP, port and direction.", 11f, Color.DKGRAY))
        }
        tcpHostContainer.visibility = if (wifiListenCheck.isChecked) View.GONE else View.VISIBLE
        content.addView(wifiPanel, margins(top = 4, bottom = 4))

        methodGroup.setOnCheckedChangeListener { _, _ ->
            if (syncingConnectionUi) return@setOnCheckedChangeListener
            val wifi = wifiRadio.isChecked
            prefs.edit().putBoolean(PREF_WIFI_MODE, wifi).apply()
            if (transport?.isConnected == true) disconnectCurrent(userInitiated = true)
            usbAutoPaused = wifi
            modeSpinner.setSelection((if (wifi) ConnectionMode.TCP else ConnectionMode.USB).ordinal)
            updateAdvancedVisibility()
            refreshSelectedMode(startDiscovery = false)
        }
        (if (prefs.getBoolean(PREF_WIFI_MODE, false)) wifiRadio else cableRadio).isChecked = true
        payAccountInput = input("").apply {
            hint = "Optional - 20 characters from CardBiz"
            filters = arrayOf(InputFilter.LengthFilter(20))
        }

        content.addView(label("AMOUNT"))
        amountInput = input("0.01").apply {
            gravity = Gravity.CENTER
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            selectAll()
        }
        content.addView(amountInput, matchWrap())

        content.addView(label("REFERENCE"))
        val referenceRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        transactionInput = input(newReference()).apply {
            filters = arrayOf(InputFilter.LengthFilter(20))
            textSize = 14f
        }
        referenceRow.addView(transactionInput, weighted())
        referenceRow.addView(secondaryButton("New") { transactionInput.setText(newReference()) })
        content.addView(referenceRow, matchWrap())

        testModeCheck = CheckBox(this).apply {
            text = "Confirmed: terminal uses a TEST merchant profile"
            setTextColor(DANGER)
            setOnCheckedChangeListener { _, _ -> updateActionState() }
        }
        content.addView(testModeCheck, margins(top = 8))

        pingButton = primaryButton("1  CONNECT TEST / PING") { execute(EcrCommandType.PING) }
        content.addView(pingButton, margins(top = 6))

        content.addView(horizontal().apply {
            payButton = primaryButton("2  PAY BY\nCARD") { execute(EcrCommandType.PURCHASE, PaymentRoute.CARD) }.apply {
                textSize = 17f
                minHeight = dp(72)
                backgroundTintList = ColorStateList.valueOf(BLUE)
            }
            ewalletButton = primaryButton("3  PAY BY\nE-WALLET QR") {
                execute(EcrCommandType.PURCHASE, PaymentRoute.EWALLET_QR)
            }.apply {
                textSize = 17f
                minHeight = dp(72)
                backgroundTintList = ColorStateList.valueOf(PINK)
            }
            addView(payButton, weighted().apply { rightMargin = dp(4) })
            addView(ewalletButton, weighted().apply { leftMargin = dp(4) })
        }, margins(top = 8))

        content.addView(text("E-wallet QR: terminal displays a DuitNow QR (M1=99) for the customer to scan with TNG, Boost, GrabPay or MAE. Card: M1=01. Both unlock after Ping and test-mode confirmation.", 12f, Color.DKGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(6))
        })

        duitnowButton = secondaryButton("Alt: terminal camera scans the customer's wallet QR (M1=02)") {
            execute(EcrCommandType.PURCHASE, PaymentRoute.SCAN_CUSTOMER_QR)
        }.apply { setTextColor(TEAL) }
        content.addView(duitnowButton, matchWrap())

        lastTransactionButton = secondaryButton("LAST SALE") { execute(EcrCommandType.LAST_TRANSACTION) }
        content.addView(lastTransactionButton, matchWrap())

        content.addView(horizontal().apply {
            batchButton = secondaryButton("BATCH DETAIL") { execute(EcrCommandType.BATCH_DETAIL) }
            cancelButton = secondaryButton("STOP / CANCEL") { stopAndSendCancel() }.apply {
                setTextColor(DANGER)
            }
            addView(batchButton, weighted())
            addView(cancelButton, weighted())
        }, matchWrap())

        content.addView(sectionTitle("Payment result"))
        parsedText = text("Connect a terminal, then tap Connect Test / Ping.", 14f, INK).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, 10)
            minHeight = dp(130)
        }
        content.addView(parsedText, matchWrap())

        val advancedToggle = secondaryButton("CONNECTION & DIAGNOSTICS") {
            advancedPanel.visibility = if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        content.addView(advancedToggle, margins(top = 14))
        advancedPanel = buildAdvancedPanel().apply { visibility = View.GONE }
        content.addView(advancedPanel, matchWrap())

        root.addView(content, matchWrap())
        updateActionState()
        return ScrollView(this).apply { addView(root) }
    }

    private fun buildAdvancedPanel(): LinearLayout = vertical().apply {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(text("USB is automatic. Approve Android's USB prompt the first time; unplugging disconnects and plugging back in reconnects.", 13f, Color.DKGRAY))

        modeSpinner = spinner(ConnectionMode.entries.map { it.label })
        addView(labeled("CONNECTION MODE", modeSpinner))

        deviceSpinner = spinner(listOf("No device detected"))
        deviceContainer = labeled("DEVICE", deviceSpinner)
        addView(deviceContainer)

        bluetoothUuidInput = input(SPP_UUID.toString())
        bluetoothUuidContainer = labeled("BLUETOOTH RFCOMM UUID", bluetoothUuidInput)
        addView(bluetoothUuidContainer)
        addView(text("CardBiz protocol name: com.castles.protocolCastles", 11f, Color.DKGRAY))

        addView(text("WiFi IP and port are set on the main screen under CONNECTION.", 11f, Color.DKGRAY))

        addView(sectionTitle("Payment routes (Merchant Index M1)"))
        PaymentRoute.entries.forEach { route ->
            addView(text("M1=${route.merchantIndex}  ${route.title}: ${route.terminalInstruction}", 11f, Color.DKGRAY))
        }

        addView(labeled("PAY ACCOUNT ID (OPTIONAL FOR PURCHASE)", payAccountInput))
        addView(text("Only Verify ID, Last Sale, Batch Detail and Settlement require this 20-character value. Do not enter MID or TID here.", 11f, Color.DKGRAY))

        addView(horizontal().apply {
            addView(secondaryButton("REFRESH") { refreshSelectedMode(startDiscovery = true) }, weighted())
            addView(secondaryButton("CONNECT") {
                usbAutoPaused = false
                connectSelected()
            }, weighted())
            addView(secondaryButton("DISCONNECT") { disconnectCurrent(userInitiated = true) }, weighted())
        }, matchWrap())

        addView(sectionTitle("Settlement - test terminal only"))
        settlementCheck = CheckBox(this@MainActivity).apply {
            text = "Allow settlement (changes the current test batch)"
            setTextColor(DANGER)
        }
        addView(settlementCheck)
        addView(secondaryButton("SEND SETTLEMENT") {
            if (!testModeCheck.isChecked || !settlementCheck.isChecked) {
                toast("Confirm both test-mode and settlement safety checks")
            } else {
                execute(EcrCommandType.SETTLEMENT)
            }
        })

        addView(sectionTitle("Raw protocol log"))
        addView(secondaryButton("CLEAR LOG") {
            synchronized(logBuffer) { logBuffer.clear() }
            logText.text = ""
        })
        addView(logText, matchWrap())
    }

    private fun wireModeSelection() {
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAdvancedVisibility()
                refreshSelectedMode(startDiscovery = false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateAdvancedVisibility()
    }

    private fun selectedMode(): ConnectionMode = ConnectionMode.entries[modeSpinner.selectedItemPosition]

    private fun updateAdvancedVisibility() {
        val mode = selectedMode()
        deviceContainer.visibility = if (mode == ConnectionMode.TCP) View.GONE else View.VISIBLE
        bluetoothUuidContainer.visibility = if (mode == ConnectionMode.BLUETOOTH) View.VISIBLE else View.GONE
        if (::wifiPanel.isInitialized) {
            wifiPanel.visibility = if (mode == ConnectionMode.TCP) View.VISIBLE else View.GONE
            if (mode == ConnectionMode.TCP) wifiDeviceIpText.text = "This device's WiFi IP: ${deviceIpAddress() ?: "not on WiFi"}"
            syncingConnectionUi = true
            when (mode) {
                ConnectionMode.TCP -> wifiRadio.isChecked = true
                ConnectionMode.USB -> cableRadio.isChecked = true
                ConnectionMode.BLUETOOTH -> Unit
            }
            syncingConnectionUi = false
        }
    }

    private fun deviceIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun refreshSelectedMode(startDiscovery: Boolean) {
        when (selectedMode()) {
            ConnectionMode.USB -> {
                refreshUsbDevices()
                if (!usbAutoPaused) autoDetectUsb()
            }
            ConnectionMode.BLUETOOTH -> refreshBluetoothDevices(startDiscovery)
            ConnectionMode.TCP -> setDeviceLabels(listOf("WiFi TCP/IP socket"))
        }
    }

    private fun handleUsbAttached(device: UsbDevice) {
        appendLog("USB attached: ${usbLabel(device)}")
        usbAutoPaused = false
        refreshUsbDevices()
        if (transport?.isConnected != true) {
            modeSpinner.setSelection(ConnectionMode.USB.ordinal)
            connectUsbDevice(device)
        }
    }

    private fun handleUsbDetached(device: UsbDevice) {
        appendLog("USB detached: ${usbLabel(device)}")
        refreshUsbDevices()
        if (connectedUsbDeviceId == device.deviceId || selectedMode() == ConnectionMode.USB) {
            disconnectCurrent(
                userInitiated = false,
                nextStatus = "Terminal unplugged",
                nextDetail = "Waiting - plug in USB/OTG to reconnect automatically",
            )
        }
    }

    private fun handleUsbPermission(intent: Intent) {
        val device = usbDevice(intent)
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
            appendLog("USB permission granted")
            connectTransport(UsbSerialTransport(usbManager, device), device.deviceId)
        } else {
            connectionInProgress = false
            setConnectionStatus("USB permission needed", false, "Approve the Android USB prompt to auto-connect")
            appendLog("USB permission denied")
        }
    }

    private fun autoDetectUsb() {
        if (usbAutoPaused || connectionInProgress || transport?.isConnected == true) return
        if (::wifiRadio.isInitialized && wifiRadio.isChecked) {
            setConnectionStatus("WiFi mode", false, "Enter the terminal IP and port, then tap Connect WiFi")
            return
        }
        refreshUsbDevices()
        val device = usbDevices.firstOrNull(::hasBulkSerialEndpoints)
        if (device == null) {
            setConnectionStatus("Waiting for terminal", false, "Plug in USB/OTG - connection is automatic")
            return
        }
        val index = usbDevices.indexOfFirst { it.deviceId == device.deviceId }
        if (index >= 0) deviceSpinner.setSelection(index)
        appendLog("Auto-detected USB serial device: ${usbLabel(device)}")
        connectUsbDevice(device)
    }

    private fun connectUsbDevice(device: UsbDevice) {
        if (connectionInProgress || connectedUsbDeviceId == device.deviceId && transport?.isConnected == true) return
        if (!hasBulkSerialEndpoints(device)) {
            setConnectionStatus("Unsupported USB device", false, "No bulk serial IN/OUT interface was found")
            appendLog("USB device has no bulk IN/OUT endpoint pair")
            return
        }
        if (usbManager.hasPermission(device)) {
            connectTransport(UsbSerialTransport(usbManager, device), device.deviceId)
        } else {
            connectionInProgress = true
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val permissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            setConnectionStatus("USB terminal detected", false, "Waiting for Android USB permission")
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, device.deviceId, permissionIntent, flags))
        }
    }

    private fun hasBulkSerialEndpoints(device: UsbDevice): Boolean {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val candidate = device.getInterface(interfaceIndex)
            var hasIn = false
            var hasOut = false
            for (endpointIndex in 0 until candidate.endpointCount) {
                val endpoint = candidate.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
            if (hasIn && hasOut) return true
        }
        return false
    }

    @Suppress("MissingPermission")
    private fun refreshBluetoothDevices(startDiscovery: Boolean) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setDeviceLabels(listOf("Bluetooth unavailable"))
            return
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            setDeviceLabels(listOf("Grant Nearby Devices permission"))
            return
        }
        bluetoothDevices.clear()
        adapter.bondedDevices.orEmpty().sortedBy(::safeBluetoothName).forEach(::addBluetoothDevice)
        updateBluetoothLabels()
        if (startDiscovery) {
            adapter.cancelDiscovery()
            if (adapter.startDiscovery()) appendLog("Bluetooth discovery started")
        }
    }

    @Suppress("MissingPermission")
    private fun addBluetoothDevice(device: BluetoothDevice) {
        if (bluetoothDevices.none { it.address == device.address }) {
            bluetoothDevices += device
            updateBluetoothLabels()
            appendLog("Bluetooth found: ${safeBluetoothName(device)} / ${device.address}")
        }
    }

    @Suppress("MissingPermission")
    private fun updateBluetoothLabels() {
        setDeviceLabels(
            bluetoothDevices.map { "${safeBluetoothName(it)} (${it.address})" }
                .ifEmpty { listOf("No paired Bluetooth terminal") },
        )
    }

    @Suppress("MissingPermission")
    private fun safeBluetoothName(device: BluetoothDevice): String = runCatching { device.name }.getOrNull() ?: "Unnamed"

    private fun refreshUsbDevices() {
        usbDevices.clear()
        usbDevices += usbManager.deviceList.values.sortedWith(
            compareByDescending<UsbDevice>(::hasBulkSerialEndpoints).thenBy { it.deviceId },
        )
        if (selectedMode() == ConnectionMode.USB) {
            setDeviceLabels(usbDevices.map(::usbLabel).ifEmpty { listOf("Waiting for USB/OTG terminal") })
        }
    }

    private fun usbLabel(device: UsbDevice): String {
        val serial = if (hasBulkSerialEndpoints(device)) "serial" else "not serial"
        return "USB %04X:%04X - %s".format(device.vendorId, device.productId, serial)
    }

    private fun setDeviceLabels(labels: List<String>) {
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun connectSelected() {
        if (transport?.isConnected == true) {
            toast("Terminal is already connected")
            return
        }
        try {
            when (selectedMode()) {
                ConnectionMode.USB -> {
                    val device = usbDevices.getOrNull(deviceSpinner.selectedItemPosition)
                        ?: throw IllegalArgumentException("Plug in a USB/OTG terminal")
                    connectUsbDevice(device)
                }
                ConnectionMode.BLUETOOTH -> {
                    if (!hasBluetoothPermissions()) {
                        requestBluetoothPermissions()
                        return
                    }
                    val device = bluetoothDevices.getOrNull(deviceSpinner.selectedItemPosition)
                        ?: throw IllegalArgumentException("Select a paired Bluetooth terminal")
                    val uuid = UUID.fromString(bluetoothUuidInput.text.toString().trim())
                    connectTransport(BluetoothRfcommTransport(bluetoothAdapter!!, device, uuid))
                }
                ConnectionMode.TCP -> {
                    val host = tcpHostInput.text.toString().trim()
                    val port = tcpPortInput.text.toString().toIntOrNull()
                        ?: throw IllegalArgumentException("Enter the TCP port supplied by CardBiz")
                    require(port in 1..65535) { "TCP port must be between 1 and 65535" }
                    getPreferences(Context.MODE_PRIVATE).edit()
                        .putString(PREF_WIFI_HOST, host)
                        .putString(PREF_WIFI_PORT, port.toString())
                        .apply()
                    if (wifiListenCheck.isChecked) {
                        appendLog("WiFi listen: waiting for terminal on ${deviceIpAddress() ?: "?"}:$port")
                        connectTransport(TcpListenTransport(port))
                    } else {
                        require(host.isNotEmpty()) { "Enter the terminal IP address" }
                        connectTransport(TcpTransport(host, port))
                    }
                }
            }
        } catch (error: Exception) {
            toast(error.message ?: "Invalid connection settings")
        }
    }

    private fun connectTransport(candidate: EcrTransport, usbDeviceId: Int? = null) {
        if (connectionInProgress) return
        connectionInProgress = true
        val generation = ++connectionGeneration
        setConnectionStatus("Connecting...", false, candidate.displayName)
        activeTask = worker.submit {
            try {
                candidate.connect()
                if (generation != connectionGeneration) {
                    candidate.disconnect()
                    return@submit
                }
                transport = candidate
                connectedUsbDeviceId = usbDeviceId
                pingSucceeded = false
                connectionInProgress = false
                appendLog("Connected: ${candidate.displayName}")
                runOnUiThread {
                    setConnectionStatus("Terminal connected", true, candidate.displayName)
                    parsedText.text = "Connected. Tap Connect Test / Ping to begin."
                    updateActionState()
                }
            } catch (error: Exception) {
                candidate.disconnect()
                connectionInProgress = false
                appendLog("Connection failed: ${error.message}")
                runOnUiThread {
                    setConnectionStatus("Connection failed", false, error.message ?: "Check cable or driver")
                    updateActionState()
                }
            }
        }
    }

    private fun disconnectCurrent(
        userInitiated: Boolean,
        nextStatus: String = if (userInitiated) "Disconnected" else "Terminal disconnected",
        nextDetail: String = if (userInitiated) "Tap Connect or replug USB to resume" else "Waiting for terminal",
    ) {
        if (userInitiated && selectedMode() == ConnectionMode.USB) usbAutoPaused = true
        connectionGeneration++
        connectionInProgress = false
        activeTask?.cancel(true)
        val current = transport
        transport = null
        connectedUsbDeviceId = null
        pingSucceeded = false
        updateActionState()
        setConnectionStatus(nextStatus, false, nextDetail)
        worker.submit {
            current?.disconnect()
            appendLog(nextStatus)
        }
    }

    private fun execute(type: EcrCommandType, route: PaymentRoute? = null) {
        val current = transport
        if (current?.isConnected != true) {
            toast("Terminal is not connected")
            return
        }
        if (type == EcrCommandType.PURCHASE) {
            if (!pingSucceeded) {
                toast("Run Connect Test / Ping first")
                return
            }
            if (!testModeCheck.isChecked) {
                toast("Confirm that the terminal uses a test merchant profile")
                return
            }
        }
        val requestedAmount = amountInput.text.toString().trim()
        val requestedReference = transactionInput.text.toString().trim()
        val request = try {
            EcrCommands.build(
                EcrCommandInput(
                    type = type,
                    payAccountId = payAccountInput.text.toString(),
                    transactionId = requestedReference,
                    amountText = requestedAmount,
                    merchantIndex = route?.merchantIndex ?: "",
                ),
            )
        } catch (error: Exception) {
            toast(error.message ?: "Check the command fields")
            return
        }

        val terminalInstruction = route?.terminalInstruction ?: "Follow instructions on the payment terminal"
        val processingTitle = route?.let { "${it.title} payment" } ?: shortTitle(type)
        setConnectionStatus("Processing $processingTitle...", true, terminalInstruction)
        parsedText.text = "Waiting for terminal response..."
        activeTask = worker.submit {
            try {
                val responses = EcrSession(current, ::appendLog).execute(request)
                val lastResponse = responses.lastOrNull()
                val approved = if (type == EcrCommandType.PING) {
                    lastResponse?.transactionCode == "FF" && lastResponse.responseCode in setOf("00", "CP")
                } else {
                    lastResponse?.isApprovedResponseFor(type.code) == true
                }
                if (type == EcrCommandType.PING) pingSucceeded = approved
                val diagnostics = responses.mapIndexed { index, response ->
                    "Response ${index + 1}/${responses.size}\n${response.summary()}\n\nRAW\n${EcrCodec.toHex(response.raw)}"
                }.joinToString("\n\n")
                val displayResult = if (type == EcrCommandType.PURCHASE) {
                    purchaseResult(lastResponse, approved, requestedAmount, requestedReference, route ?: PaymentRoute.CARD)
                } else {
                    diagnostics
                }
                runOnUiThread {
                    parsedText.text = displayResult
                    if (type == EcrCommandType.PURCHASE) {
                        parsedText.textSize = 20f
                        parsedText.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        parsedText.setTextColor(if (approved) SUCCESS else DANGER)
                        val title = if (approved) "PAYMENT SUCCESS" else "PAYMENT FAILED"
                        val detail = if (approved) {
                            "RM $requestedAmount approved via ${route?.title ?: "card"}"
                        } else {
                            lastResponse?.fieldValue("02")?.takeIf { it.isNotBlank() }
                                ?: "Response ${lastResponse?.responseCode ?: "missing"}"
                        }
                        setConnectionStatus(title, approved, detail)
                        if (!approved) statusCard.background = rounded(DANGER, 12)
                    } else {
                        parsedText.textSize = 14f
                        parsedText.typeface = Typeface.MONOSPACE
                        parsedText.setTextColor(INK)
                        val result = if (approved) "APPROVED" else "RESPONSE RECEIVED"
                        setConnectionStatus(result, approved, shortTitle(type))
                    }
                    updateActionState()
                }
            } catch (_: InterruptedException) {
                appendLog("Operation interrupted")
                runOnUiThread { setConnectionStatus("Operation stopped", false, "Cancel command queued if possible") }
            } catch (error: Exception) {
                appendLog("Command failed: ${error.message}")
                runOnUiThread {
                    parsedText.text = "ERROR\n${error.message}"
                    setConnectionStatus("Command failed", false, error.message ?: "Check diagnostics")
                    updateActionState()
                }
            }
        }
    }

    private fun purchaseResult(
        response: DecodedEcrMessage?,
        approved: Boolean,
        requestedAmount: String,
        requestedReference: String,
        route: PaymentRoute,
    ): String {
        if (response == null) return "PAYMENT FAILED\n\nNo response from terminal"
        val reference = response.fieldValue("66")?.takeIf { it.isNotBlank() } ?: requestedReference
        if (!approved) {
            val responseText = response.fieldValue("02")?.takeIf { it.isNotBlank() } ?: "Transaction not approved"
            return "PAYMENT FAILED\n\n${route.title} payment\n$responseText\nCode: ${response.responseCode}\nReference: $reference"
        }
        val custom = response.customData()
        return buildString {
            append("PAYMENT SUCCESS\n\n")
            append(route.title).append(" payment")
            if (response.isQrTransaction()) append(" (QR)")
            append("\nAmount: RM ").append(requestedAmount)
            append("\nReference: ").append(reference)
            response.walletProductName()?.let { append("\nWallet: ").append(it) }
            response.cardLabel()?.takeIf { route == PaymentRoute.CARD || response.cardLastFour() != null }?.let {
                append("\nCard type: ").append(it)
            }
            response.cardLastFour()?.let { append("\nCard ending: **** ").append(it) }
            response.cardEntryMode()?.let { append("\nEntry: ").append(it) }
            response.fieldValue("01")?.takeIf { it.isNotBlank() }?.let { append("\nApproval: ").append(it) }
            response.fieldValue("65")?.takeIf { it.isNotBlank() }?.let { append("\nInvoice: ").append(it) }
            response.fieldValue("64")?.takeIf { it.isNotBlank() }?.let { append("\nTrace: ").append(it) }
            custom["B906"]?.takeIf { it.isNotBlank() }?.let { append("\nWallet txn: ").append(it) }
            custom["B905"]?.takeIf { it.isNotBlank() }?.let { append("\nWallet ref: ").append(it) }
            response.fieldValue("N5")?.takeIf { it.isNotBlank() }?.let { append("\nBill no: ").append(it) }
            response.fieldValue("N6")?.takeIf { it.isNotBlank() }?.let { append("\nBiz Msg ID: ").append(it) }
            response.fieldValue("06")?.takeIf { it.isNotBlank() }?.let { append("\nRRN: ").append(it) }
            response.fieldValue("50")?.takeIf { it.isNotBlank() }?.let { append("\nBatch: ").append(it) }
            response.fieldValue("16")?.takeIf { it.isNotBlank() }?.let { append("\nTerminal ID: ").append(it) }
            response.fieldValue("D1")?.takeIf { it.isNotBlank() }?.let { append("\nMerchant No: ").append(it) }
            val date = response.fieldValue("03")?.takeIf { it.length == 6 }
            val time = response.fieldValue("04")?.takeIf { it.length == 6 }
            if (date != null || time != null) {
                append("\nTerminal time: ")
                date?.let { append("20").append(it.substring(0, 2)).append('-').append(it.substring(2, 4)).append('-').append(it.substring(4, 6)) }
                time?.let { append(' ').append(it.substring(0, 2)).append(':').append(it.substring(2, 4)).append(':').append(it.substring(4, 6)) }
            }
        }
    }

    private fun stopAndSendCancel() {
        activeTask?.cancel(true)
        if (payAccountInput.text.toString().trim().length == 20) {
            appendLog("Local operation stopped; sending Cancel Transaction (XX)")
            execute(EcrCommandType.CANCEL)
        } else {
            appendLog("Local operation stopped; Cancel XX skipped because Pay Account ID is unavailable")
            setConnectionStatus("Operation stopped", false, "Cancel XX needs the 20-character Pay Account ID")
        }
    }

    private fun shortTitle(type: EcrCommandType): String = when (type) {
        EcrCommandType.PING -> "Connection test"
        EcrCommandType.VERIFY_ID -> "Verify terminal"
        EcrCommandType.PURCHASE -> "Test payment"
        EcrCommandType.LAST_TRANSACTION -> "Last sale"
        EcrCommandType.BATCH_DETAIL -> "Batch detail"
        EcrCommandType.SETTLEMENT -> "Settlement"
        EcrCommandType.CANCEL -> "Cancel"
    }

    private fun updateActionState() {
        if (!::pingButton.isInitialized) return
        val connected = transport?.isConnected == true
        setEnabled(pingButton, connected)
        val canPay = connected && pingSucceeded && testModeCheck.isChecked
        setEnabled(payButton, canPay)
        setEnabled(ewalletButton, canPay)
        setEnabled(duitnowButton, canPay)
        setEnabled(lastTransactionButton, connected)
        setEnabled(batchButton, connected)
        setEnabled(cancelButton, connected)
    }

    private fun setEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.42f
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val updated = synchronized(logBuffer) {
            logBuffer.append(time).append("  ").append(message).append('\n')
            if (logBuffer.length > MAX_LOG_CHARS) logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS)
            logBuffer.toString()
        }
        runOnUiThread { logText.text = updated }
    }

    private fun setConnectionStatus(title: String, success: Boolean, detail: String) {
        statusText.text = title
        statusDetailText.text = detail
        statusCard.background = rounded(if (success) SUCCESS else NAVY, 12)
    }

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, REQUEST_BLUETOOTH)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH && selectedMode() == ConnectionMode.BLUETOOTH) {
            refreshBluetoothDevices(startDiscovery = false)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDeviceReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDevice(intent: Intent): BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @Suppress("DEPRECATION")
    private fun usbDevice(intent: Intent): UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun label(value: String) = text(value, 12f, Color.DKGRAY).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(3))
    }

    private fun sectionTitle(value: String) = text(value, 18f, NAVY).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun input(value: String) = EditText(this).apply {
        setText(value)
        setSingleLine(true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun labeled(label: String, field: View) = vertical().apply {
        addView(this@MainActivity.label(label))
        addView(field, matchWrap())
    }

    private fun spinner(values: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        isAllCaps = false
        minHeight = dp(54)
        backgroundTintList = ColorStateList.valueOf(NAVY)
        setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun margins(top: Int = 0, bottom: Int = 0) = matchWrap().apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun newReference(): String = System.currentTimeMillis().toString().padStart(20, '0').takeLast(20)

    companion object {
        private const val TAG = "CardBizEcrTest"
        private const val PREF_WIFI_MODE = "wifi_mode"
        private const val PREF_WIFI_HOST = "wifi_host"
        private const val PREF_WIFI_PORT = "wifi_port"
        private const val PREF_WIFI_LISTEN = "wifi_listen"
        private const val REQUEST_BLUETOOTH = 1001
        private const val ACTION_USB_PERMISSION = "com.cardbiz.ecrtest.USB_PERMISSION"
        private const val MAX_LOG_CHARS = 80_000
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val NAVY = Color.rgb(18, 38, 58)
        private val BLUE = Color.rgb(23, 105, 170)
        private val TEAL = Color.rgb(0, 122, 112)
        private val PINK = Color.rgb(198, 28, 108)
        private val SUCCESS = Color.rgb(22, 118, 71)
        private val DANGER = Color.rgb(166, 27, 27)
        private val SURFACE = Color.rgb(245, 247, 250)
        private val INK = Color.rgb(25, 25, 25)
    }
}
