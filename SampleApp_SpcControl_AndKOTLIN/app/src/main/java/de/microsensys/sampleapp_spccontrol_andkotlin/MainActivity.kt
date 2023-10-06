package de.microsensys.sampleapp_spccontrol_andkotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.ActivityCompat
import de.microsensys.exceptions.MssException
import de.microsensys.spc_control.RawDataReceived
import de.microsensys.spc_control.ReaderHeartbeat
import de.microsensys.spc_control.SpcInterfaceCallback
import de.microsensys.spc_control.SpcInterfaceControl
import de.microsensys.utils.PermissionFunctions
import de.microsensys.utils.PortTypeEnum
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sp_DeviceToConnect: Spinner
    private lateinit var bt_Connect: Button
    private lateinit var bt_Disconnect: Button
    private lateinit var et_TidRead: EditText
    private lateinit var bt_Read: Button
    private lateinit var et_DataRead: EditText
    private lateinit var bt_Write: Button
    private lateinit var et_DataToWrite: EditText
    private lateinit var et_Logging: EditText
    private lateinit var tv_ReaderID: TextView
    private lateinit var tv_BatStatus: TextView
    private lateinit var tv_ResultColor: TextView
    private var mSpcInterfaceControl: SpcInterfaceControl? = null
    private var mCheckThread: CheckConnectingReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sp_DeviceToConnect = findViewById(R.id.spinner_device)
        bt_Connect = findViewById(R.id.button_connect)
        bt_Connect.setOnClickListener {
            connect()
        }
        bt_Disconnect = findViewById(R.id.button_disconnect)
        bt_Disconnect.isEnabled = false
        bt_Disconnect.setOnClickListener {
            disconnect()
        }
        et_TidRead = findViewById(R.id.edit_tidRead)
        bt_Read = findViewById(R.id.button_read)
        bt_Read.isEnabled = false
        bt_Read.setOnClickListener {
                sendReadRequest()
        }
        et_DataRead = findViewById(R.id.edit_textRead)
        bt_Write = findViewById(R.id.button_write)
        bt_Write.isEnabled = false
        bt_Write.setOnClickListener {
                sendWriteRequest()
        }
        et_DataToWrite = findViewById(R.id.edit_textToWrite)
        et_DataToWrite.requestFocus()
        et_Logging = findViewById(R.id.edit_logging)
        tv_ReaderID = findViewById(R.id.textView_ReaderId)
        tv_BatStatus = findViewById(R.id.textView_BatStatus)
        tv_ResultColor = findViewById(R.id.resultColor)
        tv_ResultColor.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onPostResume() {
        super.onPostResume()

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions = PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            et_Logging.append("Allow permissions...")
            requestPermissions(neededPermissions, 0)
            return
        }
        et_Logging.append("Permissions granted...")
        //Fill spinner with list of paired POCKETwork devices
        val deviceNames: MutableList<String> = ArrayList()
        val mAdapter = BluetoothAdapter.getDefaultAdapter()
        if (mAdapter != null) {
            //List of connected devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Already requested in snippet above, but Android Studio throws an error because not explicitly checked for the exception in code
                    return
                }
            }
            val pairedDevices = mAdapter.bondedDevices
            if (pairedDevices.size > 0) {
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
        sp_DeviceToConnect.adapter = adapter
    }

    override fun onStop() {
		//For this sample, the reader must be not connected when stopped
        if (mSpcInterfaceControl != null) {
            mSpcInterfaceControl!!.closeCommunicationPort()
            mSpcInterfaceControl = null
        }
        super.onStop()
    }

    private fun appendResultText(_toAppend: String, _autoAppendNewLine: Boolean = true) {
        runOnUiThread {
            if (_autoAppendNewLine)
                et_Logging.append(_toAppend + "\n")
            else
                et_Logging.append(_toAppend)
        }
    }

    private fun connect() {
        et_Logging.setText("")

        //Before opening a new communication port, make sure that previous instance is disposed
        disposeSpcControl()
        if (sp_DeviceToConnect.selectedItemPosition == -1) {
            //TODO notify user to select a device to connect to!
            return
        }
        sp_DeviceToConnect.isEnabled = false

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        val neededPermissions =
            PermissionFunctions.getNeededPermissions(applicationContext, PortTypeEnum.Bluetooth)
        if (neededPermissions.isNotEmpty()) {
            et_Logging.append("Allow permissions and try again.")
            requestPermissions(neededPermissions, 0)
            return
        }

        //Initialize SpcInterfaceControl instance.
        //  PortType = PortTypeEnum.Bluetooth --> Bluteooth
        //  PortName = selected device in Spinner --> Device name as shown in Settings
        mSpcInterfaceControl = SpcInterfaceControl(
            this,  //Instance to this Activity
            mCallback,  //Callback where events will be notified
            PortTypeEnum.Bluetooth,  // Bluetooth
            sp_DeviceToConnect.selectedItem.toString()
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
            et_Logging.append("Connecting...")
            startCheckConnectingThread()
            bt_Connect.isEnabled = false
            bt_Disconnect.isEnabled = true
        } catch (e: MssException) {
            e.printStackTrace()
            et_Logging.append("Error opening port.")
            sp_DeviceToConnect.isEnabled = true
        }
    }

    private fun connectProcedureFinished() {
        //Open process finished
        if (mSpcInterfaceControl!!.isCommunicationPortOpen) {
            // Communication port is open
            runOnUiThread {
                et_Logging.append("\nCONNECTED\n")
                bt_Read.isEnabled = true
                bt_Write.isEnabled = true
            }
        } else {
            //Communication port is not open
            appendResultText("\n Reader NOT connected \n  -> PRESS DISCONNECT BUTTON")
        }
    }

    private fun disconnect() {
        disposeSpcControl()
        bt_Connect.isEnabled = true
        sp_DeviceToConnect.isEnabled = true
        bt_Disconnect.isEnabled = false
        bt_Read.isEnabled = false
        bt_Write.isEnabled = false
    }

    private fun sendReadRequest() {
        // Generate "SCAN" request and send to reader
        val command = "~T" //SOH + Read-Identifier
        et_TidRead.setText("")
        et_DataRead.setText("")
        tv_ResultColor.setBackgroundColor(Color.TRANSPARENT)
        mSpcInterfaceControl!!.sendSpcRequest(command)
        appendResultText("Sent READ Request: $command")
    }

    private fun sendWriteRequest() {
        //Hide the Keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(et_TidRead.windowToken, 0)

        //Check if TID EditText is empty
        val tidText = et_TidRead.text.toString()
        if (tidText.isEmpty()) {
            //TODO notify user that TID field is empty
            return
        }
        if (tidText.length != 16) {
            //TODO notify user that something is wrong with TID string
            return
        }
        val dataToWrite = et_DataToWrite.text.toString()
        if (dataToWrite.isEmpty()) {
            //TODO notify user that DataToWrite field is empty
            return
        }

        // Generate "WRITE" request and send to reader
        var command = "~W" //SOH + Write-Identifier
        command += tidText //TID
        command += dataToWrite
        tv_ResultColor.setBackgroundColor(Color.TRANSPARENT)
        mSpcInterfaceControl!!.sendSpcRequest(command)
        appendResultText("Sent WRITE Request: $command")
    }

    private val mCallback: SpcInterfaceCallback = object : SpcInterfaceCallback {
        override fun spcReaderHeartbeatReceived(readerHeartbeat: ReaderHeartbeat) {
            // Heartbeat received from reader
            runOnUiThread {
                et_Logging.append("Heartbeat received: " + readerHeartbeat.readerID + ", " + readerHeartbeat.batStatus.toString() + "\n")
                tv_ReaderID.text = String.format(
                    Locale.getDefault(),
                    "ReaderID : %d",
                    readerHeartbeat.readerID
                )
                tv_BatStatus.text = String.format("BatStatus: %s", readerHeartbeat.batStatus)
                tv_ResultColor.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        override fun spcRawDataReceived(rawDataReceived: RawDataReceived) {
            //Data received from reader
            runOnUiThread { decodeReceivedText(rawDataReceived.dataReceived) }
        }
    }

    private fun decodeReceivedText(_receivedText: String) {
        // Remove <CR> if present
        var text = _receivedText
        if (text.endsWith("\r"))
            text = text.substring(0, text.length - 1)
        appendResultText("Data received: $text")
        when (text[0]) {
            'T' -> if (text.length >= 16) { //Check if minimum length received
                // Remove "T" Identifier
                text = text.substring(1)

                //Get Personal-Nr., Ausweis-Nr. or Equi-Nr. (0-15)
                var firstData = text.substring(0, 16)
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
                et_TidRead.setText(firstData)
                et_DataRead.setText(secondData)
                tv_ResultColor.setBackgroundColor(Color.GREEN)
            }

            'R' -> {
                if (text.startsWith("RW")) {
                    //Write result
                    when (text) {
                        "RW00" ->                             // Result OK
                            tv_ResultColor.setBackgroundColor(Color.GREEN)

                        "RW24" ->                             //Transponder not found or error writing
                            tv_ResultColor.setBackgroundColor(Color.RED)
                    }
                }
                if (text.startsWith("RT")) {
                    //Read transponder error
                    if (text.startsWith("00", 2)) {
                        // Result OK
                        tv_ResultColor.setBackgroundColor(Color.GREEN)
                    } else {
                        // Error
                        // Example "RT2400" --> Transponder not found or error reading
                        tv_ResultColor.setBackgroundColor(Color.RED)
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

    private inner class CheckConnectingReader internal constructor() : Thread() {
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