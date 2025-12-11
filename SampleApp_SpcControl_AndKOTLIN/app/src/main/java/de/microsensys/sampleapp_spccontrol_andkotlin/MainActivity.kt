package de.microsensys.sampleapp_spccontrol_andkotlin

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.microsensys.exceptions.MssException
import de.microsensys.spc_control.RawDataReceived
import de.microsensys.spc_control.ReaderHeartbeat
import de.microsensys.spc_control.SpcInterfaceCallback
import de.microsensys.spc_control.SpcInterfaceControl
import de.microsensys.utils.PermissionFunctions
import de.microsensys.utils.PortTypeEnum
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var spDeviceToConnect: Spinner
    private lateinit var btConnect: Button
    private lateinit var btDisconnect: Button
    private lateinit var etTidRead: EditText
    private lateinit var btRead: Button
    private lateinit var etDataRead: EditText
    private lateinit var btWrite: Button
    private lateinit var etDataToWrite: EditText
    private lateinit var etLogging: EditText
    private lateinit var tvReaderID: TextView
    private lateinit var tvBatStatus: TextView
    private lateinit var tvResultColor: TextView

    private var mSpcInterfaceControl: SpcInterfaceControl? = null
    private var mCheckThread: CheckConnectingReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        spDeviceToConnect = findViewById(R.id.spinner_device)
        btConnect = findViewById(R.id.button_connect)
        btConnect.setOnClickListener {
            connect()
        }
        btDisconnect = findViewById(R.id.button_disconnect)
        btDisconnect.isEnabled = false
        btDisconnect.setOnClickListener {
            disconnect()
        }
        etTidRead = findViewById(R.id.edit_tidRead)
        btRead = findViewById(R.id.button_read)
        btRead.isEnabled = false
        btRead.setOnClickListener {
            sendReadRequest()
        }
        etDataRead = findViewById(R.id.edit_textRead)
        btWrite = findViewById(R.id.button_write)
        btWrite.isEnabled = false
        btWrite.setOnClickListener {
            sendWriteRequest()
        }
        etDataToWrite = findViewById(R.id.edit_textToWrite)
        etDataToWrite.requestFocus()
        etLogging = findViewById(R.id.edit_logging)
        tvReaderID = findViewById(R.id.textView_ReaderId)
        tvBatStatus = findViewById(R.id.textView_BatStatus)
        tvResultColor = findViewById(R.id.resultColor)
        tvResultColor.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onPostResume() {
        super.onPostResume()

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions = PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            etLogging.append("Allow permissions...")
            requestPermissions(neededPermissions, 0)
            return
        }
        etLogging.append("Permissions granted...")
        //Fill spinner with list of paired POCKETwork devices
        val deviceNames: MutableList<String> = ArrayList()
        val bluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val mAdapter = bluetoothManager.adapter
        if (mAdapter != null) {
            //List of connected devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Already requested in snippet above, but Android Studio throws an error because not explicitly checked for the exception in code
                    return
                }
            }
            val pairedDevices = mAdapter.bondedDevices
            if (pairedDevices.isNotEmpty()) {
                for (device in pairedDevices) {
                    if (device.name.startsWith("iID POCKETwork")) deviceNames.add(device.name)
                    if (device.name.startsWith("iID PENsolid")) deviceNames.add(device.name)
                }
            }
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, deviceNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDeviceToConnect.adapter = adapter
    }

    override fun onStop() {
        //For this sample, the reader must be not connected when stopped
        if (mSpcInterfaceControl != null) {
            mSpcInterfaceControl!!.closeCommunicationPort()
            mSpcInterfaceControl = null
        }
        super.onStop()
    }

    private fun appendResultText(toAppend: String, autoAppendNewLine: Boolean = true) {
        runOnUiThread {
            if (autoAppendNewLine)
                etLogging.append(toAppend + "\n")
            else
                etLogging.append(toAppend)
        }
    }

    private fun connect() {
        etLogging.setText("")

        //Before opening a new communication port, make sure that previous instance is disposed
        disposeSpcControl()
        if (spDeviceToConnect.selectedItemPosition == -1) {
            //TODO notify user to select a device to connect to!
            return
        }
        spDeviceToConnect.isEnabled = false

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions =
            PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            etLogging.append("Allow permissions and try again.")
            requestPermissions(neededPermissions, 0)
            return
        }

        //Initialize SpcInterfaceControl instance.
        //  PortType = PortTypeEnum.Bluetooth --> Bluetooth
        //  PortName = selected device in Spinner --> Device name as shown in Settings
        mSpcInterfaceControl = SpcInterfaceControl(
            this,  //Instance to this Activity
            mCallback,  //Callback where events will be notified
            PortTypeEnum.Bluetooth,  // Bluetooth
            spDeviceToConnect.selectedItem.toString()
        )
        //Configure DataPrefix and DataSuffix
        //  SpcInterfaceControl will automatically use this to divide the received data and call
        //      the "spcRawDataReceived" or "spcReaderHeartbeatReceived" methods from the Callback
        mSpcInterfaceControl!!.dataPrefix = ""
        mSpcInterfaceControl!!.dataSuffix = "\r\n"

        //Try to open communication port. This call does not block!!
        try {
            mSpcInterfaceControl!!.openCommunicationPort()
            //No exception --> Check for process in a separate thread
            etLogging.append("Connecting...")
            startCheckConnectingThread()
            btConnect.isEnabled = false
            btDisconnect.isEnabled = true
        } catch (e: MssException) {
            e.printStackTrace()
            etLogging.append("Error opening port.")
            spDeviceToConnect.isEnabled = true
        }
    }

    private fun connectProcedureFinished() {
        //Open process finished
        if (mSpcInterfaceControl!!.isCommunicationPortOpen) {
            // Communication port is open
            runOnUiThread {
                etLogging.append("\nCONNECTED\n")
                btRead.isEnabled = true
                btWrite.isEnabled = true
            }
        } else {
            //Communication port is not open
            appendResultText("\n Reader NOT connected \n  -> PRESS DISCONNECT BUTTON")
        }
    }

    private fun disconnect() {
        disposeSpcControl()
        btConnect.isEnabled = true
        spDeviceToConnect.isEnabled = true
        btDisconnect.isEnabled = false
        btRead.isEnabled = false
        btWrite.isEnabled = false
    }

    private fun sendReadRequest() {
        // Generate "SCAN" request and send to reader
        val command = "~T" //SOH + Read-Identifier
        etTidRead.setText("")
        etDataRead.setText("")
        tvResultColor.setBackgroundColor(Color.TRANSPARENT)
        mSpcInterfaceControl!!.sendSpcRequest(command)
        appendResultText("Sent READ Request: $command")
    }

    private fun sendWriteRequest() {
        //Hide the Keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etTidRead.windowToken, 0)

        //Check if TID EditText is empty
        val tidText = etTidRead.text.toString()
        if (tidText.isEmpty()) {
            //TODO notify user that TID field is empty
            return
        }
        if (tidText.length != 16) {
            //TODO notify user that something is wrong with TID string
            return
        }
        val dataToWrite = etDataToWrite.text.toString()
        if (dataToWrite.isEmpty()) {
            //TODO notify user that DataToWrite field is empty
            return
        }

        // Generate "WRITE" request and send to reader
        var command = "~W" //SOH + Write-Identifier
        command += tidText //TID
        command += dataToWrite
        tvResultColor.setBackgroundColor(Color.TRANSPARENT)
        mSpcInterfaceControl!!.sendSpcRequest(command)
        appendResultText("Sent WRITE Request: $command")
    }

    private val mCallback: SpcInterfaceCallback = object : SpcInterfaceCallback {
        override fun spcReaderHeartbeatReceived(readerHeartbeat: ReaderHeartbeat) {
            // Heartbeat received from reader
            runOnUiThread {
                etLogging.append("Heartbeat received: " + readerHeartbeat.readerID + ", " + readerHeartbeat.batStatus.toString() + "\n")
                tvReaderID.text = String.format(
                    Locale.getDefault(),
                    "ReaderID : %d",
                    readerHeartbeat.readerID
                )
                tvBatStatus.text = String.format("BatStatus: %s", readerHeartbeat.batStatus)
                tvResultColor.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        override fun spcRawDataReceived(rawDataReceived: RawDataReceived) {
            //Data received from reader
            runOnUiThread { decodeReceivedText(rawDataReceived.dataReceived) }
        }
    }

    private fun decodeReceivedText(receivedText: String) {
        // Remove <CR> if present
        var text = receivedText
        if (text.endsWith("\r"))
            text = text.take(text.length - 1)
        appendResultText("Data received: $text")
        when (text[0]) {
            'T' -> if (text.length >= 16) { //Check if minimum length received
                // Remove "T" Identifier
                text = text.substring(1)

                //Get Personal-Nr., Ausweis-Nr. or Equi-Nr. (0-15)
                var firstData = text.take(16)
                firstData = firstData.replaceFirst(
                    "\\x00++$".toRegex(),
                    ""
                ) //Remove possible not initialized data from the end of the String

                //Get Equi-Daten (16 - 92)
                var secondData = ""
                if (text.length > 16) {
                    secondData = text.substring(16)
                    //                        secondData = secondData.replace("\0", ""); //Remove possible not initialized data
                    secondData = secondData.replaceFirst(
                        "\\x00++$".toRegex(),
                        ""
                    ) //Remove possible not initialized data from the end of the String
                }
                etTidRead.setText(firstData)
                etDataRead.setText(secondData)
                tvResultColor.setBackgroundColor(Color.GREEN)
            }

            'R' -> {
                if (text.startsWith("RW")) {
                    //Write result
                    when (text) {
                        "RW00" ->                             // Result OK
                            tvResultColor.setBackgroundColor(Color.GREEN)

                        "RW24" ->                             //Transponder not found or error writing
                            tvResultColor.setBackgroundColor(Color.RED)
                    }
                }
                if (text.startsWith("RT")) {
                    //Read transponder error
                    if (text.startsWith("00", 2)) {
                        // Result OK
                        tvResultColor.setBackgroundColor(Color.GREEN)
                    } else {
                        // Error
                        // Example "RT2400" --> Transponder not found or error reading
                        tvResultColor.setBackgroundColor(Color.RED)
                    }
                }
            }
        }
    }

    private fun disposeSpcControl() {
        if (mSpcInterfaceControl != null) mSpcInterfaceControl!!.closeCommunicationPort()
        mSpcInterfaceControl = null
    }
    private fun startCheckConnectingThread() {
        if (mCheckThread != null) {
            mCheckThread!!.cancel()
            mCheckThread = null
        }
        mCheckThread = CheckConnectingReader()
        mCheckThread!!.start()
    }
    private inner class CheckConnectingReader : Thread() {
        private var loop = true
        override fun run() {
            while (loop) {
                if (mSpcInterfaceControl!!.isCommunicationPortOpening) {
                    //Still trying to connect -> Wait and continue
                    try {
                        sleep(200)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    appendResultText(".", false)
                    continue
                }
                //Connecting finished! Check if connected or not connected
                connectProcedureFinished()

                //Stop thread
                cancel()
            }
        }

        fun cancel() {
            loop = false
        }
    }
}