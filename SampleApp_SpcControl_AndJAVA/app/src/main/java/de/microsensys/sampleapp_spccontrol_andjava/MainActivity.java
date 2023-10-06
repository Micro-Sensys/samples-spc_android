package de.microsensys.sampleapp_spccontrol_andjava;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.microsensys.exceptions.MssException;
import de.microsensys.spc_control.RawDataReceived;
import de.microsensys.spc_control.ReaderHeartbeat;
import de.microsensys.spc_control.SpcInterfaceCallback;
import de.microsensys.spc_control.SpcInterfaceControl;
import de.microsensys.utils.PermissionFunctions;
import de.microsensys.utils.PortTypeEnum;

public class MainActivity extends AppCompatActivity {

    Spinner sp_DeviceToConnect;
    Button bt_Connect;
    Button bt_Disconnect;
    EditText et_TidRead;
    Button bt_Read;
    EditText et_DataRead;
    Button bt_Write;
    EditText et_DataToWrite;
    EditText et_Logging;
    TextView tv_ReaderID;
    TextView tv_BatStatus;
    TextView tv_ResultColor;

    SpcInterfaceControl mSpcInterfaceControl = null;
    private CheckConnectingReader mCheckThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp_DeviceToConnect = findViewById(R.id.spinner_device);
        bt_Connect = findViewById(R.id.button_connect);
        bt_Connect.setOnClickListener(v -> connect());
        bt_Disconnect = findViewById(R.id.button_disconnect);
        bt_Disconnect.setEnabled(false);
        bt_Disconnect.setOnClickListener(v -> disconnect());
        et_TidRead = findViewById(R.id.edit_tidRead);
        bt_Read = findViewById(R.id.button_read);
        bt_Read.setEnabled(false);
        bt_Read.setOnClickListener(v -> sendReadRequest());
        et_DataRead = findViewById(R.id.edit_textRead);
        bt_Write = findViewById(R.id.button_write);
        bt_Write.setEnabled(false);
        bt_Write.setOnClickListener(v -> sendWriteRequest());
        et_DataToWrite = findViewById(R.id.edit_textToWrite);
        et_DataToWrite.requestFocus();
        et_Logging = findViewById(R.id.edit_logging);
        tv_ReaderID = findViewById(R.id.textView_ReaderId);
        tv_BatStatus = findViewById(R.id.textView_BatStatus);
        tv_ResultColor = findViewById(R.id.resultColor);
        tv_ResultColor.setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        String[] neededPermissions = PermissionFunctions.getNeededPermissions(getApplicationContext(), PortTypeEnum.Bluetooth);
        if (neededPermissions.length > 0) {
            et_Logging.append("Allow permissions...");
            requestPermissions(neededPermissions, 0);
            return;
        }

        et_Logging.append("Permissions granted...");
        //Fill spinner with list of paired POCKETwork devices
        List<String> deviceNames = new ArrayList<>();
        BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter != null) {
            //List of connected devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Already requested in snippet above, but Android Studio throws an error because not explicitly checked for the exception in code
                    return;
                }
            }
            Set<BluetoothDevice> pairedDevices = mAdapter.getBondedDevices();
            if (pairedDevices.size()>0){
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName().startsWith("iID POCKETwork"))
                        deviceNames.add(device.getName());
                    if (device.getName().startsWith("iID PENsolid"))
                        deviceNames.add(device.getName());
                }
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item, deviceNames
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_DeviceToConnect.setAdapter(adapter);
    }

    @Override
    protected void onStop() {
		//For this sample, the reader must be not connected when stopped
        if (mSpcInterfaceControl != null){
            mSpcInterfaceControl.closeCommunicationPort();
            mSpcInterfaceControl = null;
        }
        super.onStop();
    }

    private void appendResultText(String _toAppend){
        appendResultText(_toAppend, true);
    }
    private void appendResultText(final String _toAppend, final boolean _autoAppendNewLine){
        runOnUiThread(() -> {
            if (_autoAppendNewLine)
                et_Logging.append(_toAppend + "\n");
            else
                et_Logging.append(_toAppend);
        });
    }

    private void connect() {
        et_Logging.setText("");

        //Before opening a new communication port, make sure that previous instance is disposed
        disposeSpcControl();
        if (sp_DeviceToConnect.getSelectedItemPosition() == -1){
            //TODO notify user to select a device to connect to!
            return;
        }
        sp_DeviceToConnect.setEnabled(false);

        //Check if there are permissions that need to be requested (USB permission is requested first when "initialize" is called)
        String[] neededPermissions = PermissionFunctions.getNeededPermissions(getApplicationContext(), PortTypeEnum.Bluetooth);
        if (neededPermissions.length > 0){
            et_Logging.append("Allow permissions and try again.");
            requestPermissions(neededPermissions, 0);
            return;
        }

        //Initialize SpcInterfaceControl instance.
        //  PortType = PortTypeEnum.Bluetooth --> Bluteooth
        //  PortName = selected device in Spinner --> Device name as shown in Settings
        mSpcInterfaceControl = new SpcInterfaceControl(
                this, //Instance to this Activity
                mCallback, //Callback where events will be notified
                PortTypeEnum.Bluetooth, // Bluetooth
                sp_DeviceToConnect.getSelectedItem().toString());
        //Configure DataPrefix and DataSuffix
        //  SpcInterfaceControl will automatically use this to divide the received data and call
        //      the "spcRawDataReceived" or "spcReaderHeartbeatReceived" methods from the Callback
        mSpcInterfaceControl.setDataPrefix("");
        mSpcInterfaceControl.setDataSuffix("\r\n");

        //Try to open communication port. This call does not block!!
        try {
            mSpcInterfaceControl.openCommunicationPort();
            //No exception --> Check for process in a separate thread
            et_Logging.append("Connecting...");
            startCheckConnectingThread();
            bt_Connect.setEnabled(false);
            bt_Disconnect.setEnabled(true);
        } catch (MssException e) {
            e.printStackTrace();

            et_Logging.append("Error opening port.");
            sp_DeviceToConnect.setEnabled(true);
        }
    }
    private void connectProcedureFinished() {
        //Open process finished
        if (mSpcInterfaceControl.getIsCommunicationPortOpen()) {
            // Communication port is open
            runOnUiThread(() -> {
                et_Logging.append("\nCONNECTED\n");
                bt_Read.setEnabled(true);
                bt_Write.setEnabled(true);
            });
        } else {
            //Communication port is not open
            appendResultText("\n Reader NOT connected \n  -> PRESS DISCONNECT BUTTON");
        }
    }

    private void disconnect() {
        disposeSpcControl();
        bt_Connect.setEnabled(true);
        sp_DeviceToConnect.setEnabled(true);
        bt_Disconnect.setEnabled(false);
        bt_Read.setEnabled(false);
        bt_Write.setEnabled(false);
    }

    private void sendReadRequest() {
        // Generate "SCAN" request and send to reader
        String command = "~T"; //SOH + Read-Identifier

        et_TidRead.setText("");
        et_DataRead.setText("");
        tv_ResultColor.setBackgroundColor(Color.TRANSPARENT);

        mSpcInterfaceControl.sendSpcRequest(command);
        appendResultText("Sent READ Request: " + command);
    }

    private void sendWriteRequest() {
        //Hide the Keyboard
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(et_TidRead.getWindowToken(), 0);
        }

        //Check if TID EditText is empty
        String tidText = et_TidRead.getText().toString();
        if (tidText.isEmpty()){
            //TODO notify user that TID field is empty
            return;
        }
        if (tidText.length() != 16){
            //TODO notify user that something is wrong with TID string
            return;
        }
        String dataToWrite = et_DataToWrite.getText().toString();
        if (dataToWrite.isEmpty()){
            //TODO notify user that DataToWrite field is empty
            return;
        }

        // Generate "WRITE" request and send to reader
        String command = "~W"; //SOH + Write-Identifier
        command += tidText; //TID
        command += dataToWrite;

        tv_ResultColor.setBackgroundColor(Color.TRANSPARENT);

        mSpcInterfaceControl.sendSpcRequest(command);
        appendResultText("Sent WRITE Request: " + command);
    }

    private final SpcInterfaceCallback mCallback = new SpcInterfaceCallback() {
        @Override
        public void spcReaderHeartbeatReceived(final ReaderHeartbeat readerHeartbeat) {
            // Heartbeat received from reader
            runOnUiThread(() -> {
                et_Logging.append("Heartbeat received: " + readerHeartbeat.getReaderID() + ", " + readerHeartbeat.getBatStatus().toString() + "\n");
                tv_ReaderID.setText(String.format(Locale.getDefault(), "ReaderID : %d", readerHeartbeat.getReaderID()));
                tv_BatStatus.setText(String.format("BatStatus: %s", readerHeartbeat.getBatStatus()));
                tv_ResultColor.setBackgroundColor(Color.TRANSPARENT);
            });
        }

        @Override
        public void spcRawDataReceived(final RawDataReceived rawDataReceived) {
            //Data received from reader
            runOnUiThread(() -> decodeReceivedText(rawDataReceived.getDataReceived()));
        }
    };

    private void decodeReceivedText(String _receivedText) {
        // Remove <CR> if present
        if (_receivedText.endsWith("\r"))
            _receivedText = _receivedText.substring(0, _receivedText.length()-1);
        appendResultText("Data received: " + _receivedText);
        switch ( _receivedText.charAt(0)){
            case 'T': //Transponder read. Decode TID and Data from received String
                if (_receivedText.length() >= 16){ //Check if minimum length received
                    // Remove "T" Identifier
                    _receivedText = _receivedText.substring(1);

                    //Get Personal-Nr., Ausweis-Nr. or Equi-Nr. (0-15)
                    String firstData = _receivedText.substring(0, 16);
                    firstData = firstData.replaceFirst("\\x00++$",""); //Remove possible not initialized data from the end of the String

                    //Get Equi-Daten (16 - 92)
                    String secondData = "";
                    if (_receivedText.length() > 16){
                        secondData = _receivedText.substring(16);
//                        secondData = secondData.replace("\0", ""); //Remove possible not initialized data
                        secondData = secondData.replaceFirst("\\x00++$",""); //Remove possible not initialized data from the end of the String
                    }

                    et_TidRead.setText(firstData);
                    et_DataRead.setText(secondData);
                    tv_ResultColor.setBackgroundColor(Color.GREEN);
                }
                break;
            case 'R': //Result String
                if  (_receivedText.startsWith("RW")){
                    //Write result
                    switch (_receivedText){
                        case "RW00":
                            // Result OK
                            tv_ResultColor.setBackgroundColor(Color.GREEN);
                            break;
                        case "RW24":
                            //Transponder not found or error writing
                            tv_ResultColor.setBackgroundColor(Color.RED);
                            break;
                    }
                }
                if  (_receivedText.startsWith("RT")){
                    //Read transponder error
                    if (_receivedText.startsWith("00", 2)){
                        // Result OK
                        tv_ResultColor.setBackgroundColor(Color.GREEN);
                    }
                    else{
                        // Error
                        // Example "RT2400" --> Transponder not found or error reading
                        tv_ResultColor.setBackgroundColor(Color.RED);
                    }
                }
                break;
        }
    }

    private void disposeSpcControl(){
        if (mSpcInterfaceControl != null)
            mSpcInterfaceControl.closeCommunicationPort();
        mSpcInterfaceControl = null;
    }
    private void startCheckConnectingThread() {
        if (mCheckThread!=null){
            mCheckThread.cancel();
            mCheckThread=null;
        }
        mCheckThread = new CheckConnectingReader();
        mCheckThread.start();
    }
    private class CheckConnectingReader extends Thread {
        private boolean loop;

        CheckConnectingReader(){
            loop = true;
        }

        @Override
        public void run() {
            while (loop){
                if (mSpcInterfaceControl.getIsCommunicationPortOpening()){
                    //Still trying to connect -> Wait and continue
                    try {
                        //noinspection BusyWait
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    appendResultText(".", false);
                    continue;
                }
                //Connecting finished! Check if connected or not connected
                connectProcedureFinished();

                //Stop thread
                cancel();
            }
        }

        void cancel(){
            loop = false;
        }
    }
}