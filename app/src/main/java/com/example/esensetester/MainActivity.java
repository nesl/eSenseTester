package com.example.esensetester;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.esensetester.esenselib.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ESenseSensorListener,ESenseConnectionListener, ESenseEventListener {


    //LOG ID
    final private String TAG = "DBG-MainActivity";

    //UI Elements
    Button submitDeviceName;
    EditText deviceNameBox; //OnClick registered to beginTracking
    TextView statusBox;
    TextView acc_table;
    TextView gyro_table;

    //Location permissions
    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 999;

    //Bluetooth manager
    private BluetoothManager mBluetoothManager = null;
    private BluetoothAdapter mBtAdapter = null;

    //ESenseManager
    ESenseManager manager = null;
    //Sampling Rate?
    private int sampling_rate = 100; //1 is the min, 100 is the max.

    //ESenseConfig
    ESenseConfig esg = null;
    boolean received_configs = false;

    // Sampling Rate Measurements
    long last_sampled_time = 0;
    long last_sampled_time_2 = 0;
    int num_acc_samples = 0;
    int num_gyro_samples = 0;

    //Timeout for Bluetooth connections in milliseconds
    int delay_milliseconds = 2000;

    //Message to write to file
    String messageToWrite = "";

    //Data Exporter
    dataExporter dex;

    //Starting/Ending connection
    boolean isConnected = false;

    //Threads for Writing
    List<String> writeQueue;  //This is created from a synchronized list
    Thread writeThread;
    long last_written_time = 0;

    //For calculating the mean and standard deviation of received packets:
    double mean = 0.0;
    double std_dev = 0.0;
    long total_recording_time = 5; //seconds for outputting the mean and std dev



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        submitDeviceName = (Button)findViewById(R.id.button);
        deviceNameBox   = (EditText)findViewById(R.id.deviceNameBox); //OnClick registered to beginTracking
        statusBox = (TextView)findViewById(R.id.statusBox);
        acc_table = (TextView) findViewById(R.id.acc_table);
        gyro_table = (TextView) findViewById(R.id.gyro_table);



        //Request location permissions for BLE use
        requestLocationPermissions();
        //Request write permissions
        requestExternalWriteAccess();

        //Initialize Bluetooth manager
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(this.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.d(TAG, "ERROR! Unable to initialize BluetoothManager.");
            }
        }
        //Get bluetooth adapter
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBtAdapter == null) {
            Log.d(TAG, "Device does not have Bluetooth capability!");
        }

        //Create data exporter
        dex = new dataExporter();

        //Create Queue for messages to be written to file
        writeQueue = Collections.synchronizedList(new ArrayList<String>());

        //Run a background thread for writing to CSV files
        writeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true){
                    writeToFile();
                }
            }
        });
        writeThread.start();

    }


    public void requestExternalWriteAccess() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    100);
        }
    }


    public void requestLocationPermissions() {

        //Check to see if this app can get location access
        if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("This app needs location access");
            builder.setMessage("Please grant location access so this app can detect beacons.");
            builder.setPositiveButton(android.R.string.ok, null);
            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                }
            });
            builder.show();
        }
    }

    //Scan Bluetooth Devices.
    private void scanDevices() {
        mBtAdapter.getBluetoothLeScanner().startScan(mLeScanCallback);
    }

    private ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {


            final BluetoothDevice device = result.getDevice();
            final int rssi = result.getRssi();
            String deviceName = device.getName();
            String deviceAddr = device.getAddress();

            if (deviceName != null && deviceName.contains("eSense")) {
                Log.d(TAG, "Device Name: .." + deviceName + ".. Device Addr: " + deviceAddr);
            }



        }
    };




    public void beginTracking(View view) {

        if (isConnected) {
            manager.disconnect();
            submitDeviceName.setText("CONNECT FROM DEVICE");
            isConnected = false;

        }

        else {
            String devicename = deviceNameBox.getText().toString();
            String deviceAddr = "00:04:79:00:0E:D6";
            if(devicename.equals("")) {
                //devicename = "eSense-1171";  // Sometimes if one doesn't work, try the other earbud
                devicename = "eSense-0798";  // Device Addr = 00:04:79:00:0E:D6
                //devicename = "(.*)eSense(.*)";  // Use RegEx expression for being desperate

            }

            Log.d(TAG, "Begin Tracking Device " + devicename);

            manager = new ESenseManager(devicename,
                    MainActivity.this.getApplicationContext(), this);
            statusBox.setText("Scanning for Devices...");

            // Only scan bluetooth devices for debugging
            //scanDevices();

            manager.connect(delay_milliseconds);
            submitDeviceName.setText("DISCONNECT FROM DEVICE");
            isConnected = true;
        }



    }

    //Functions for writing to file
    private void writeToFile() {
        if(!writeQueue.isEmpty()) {

            if(System.currentTimeMillis() > last_written_time + 1000) {

                last_written_time = System.currentTimeMillis();
                Log.d(TAG, "Current Queue Size: " + Long.toString(writeQueue.size()));
            }

            //Get the first object in the writeQueue
            String toWrite = writeQueue.get(0);
            //Remove the first object from the queue
            writeQueue.remove(0);
            //Buffer the data for writing to a CSV
            dex.directWriteToFile(toWrite);

        }
    }

    //Write the rest of the writeQueue to file - this is called before this service is destroyed
    private void completeWriting() {
        Log.d(TAG, " Completing Writes to file!");
        synchronized (writeQueue) {
            Iterator i = writeQueue.iterator(); // Must be in synchronized block
            while (i.hasNext()) {
                String toWrite = (String) i.next();
                dex.directWriteToFile(toWrite);
            }
            writeQueue.clear();
        }
        Log.d(TAG, " Completed all Writes!");

    }







    @Override  //Android now needs Coarse access location to do BT scans - Idk why but it wont work otherwise
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[],
                                           int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Permissions", "fine location permission granted");
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Functionality limited");
                    builder.setMessage("Since location access has not been granted, this app will not be able to discover beacons when in the background.");
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                        }
                    });
                    builder.show();
                }
                return;
            }
        }
    }

    public void displayStatus(final String stat) {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                statusBox.setText(stat);
            }
        });
    }
    public void displayACCData(final String data) {

        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                acc_table.setText(data);
            }
        });
    }
    public void displayGYROData(final String data) {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                gyro_table.setText(data);
            }
        });
    }


    // All methods relevant to sensor Connection listener

    @Override
    public void onDeviceFound(ESenseManager eSenseManager) {

        Log.d(TAG, "Found Device During Scan! ");
        displayStatus("Found Device!");
    }

    @Override
    public void onDeviceNotFound(ESenseManager eSenseManager) {

        Log.d(TAG, "Did not find device during scan!");
        displayStatus("Did not find Device!");

    }

    Handler handler = new Handler(new Handler.Callback() {

        @Override
        public boolean handleMessage(Message msg) {

            // Get Sensor configuration
            boolean sensor_configs = manager.getSensorConfig();
            Log.d(TAG, "Received Sensor Configuration! " + sensor_configs);
            return sensor_configs;
        }
    });

    @Override
    public void onConnected(ESenseManager eSenseManager) {

        Log.d(TAG, "Connected to Device!");
        displayStatus("Connected to Device!");

        //Begin Sensor Transmissions
        manager.registerSensorListener(this, sampling_rate);
        Log.d(TAG, "Register Listener for Sensors!");

        //TODO: Something is wrong with the event listener - it doesn't seem to be able to
        // retrieve the sensor configurations from the bluetoothGATT

        //Register Event listener
        boolean correctly_registered = manager.registerEventListener(this);
        Log.d(TAG, "Registered Event Listener! " + correctly_registered);


        new Thread()
        {
            public void run()
            {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Message msg = new Message();
                handler.sendMessage(msg);
                //Log.d(TAG, "BLARGH!");
            }
        }.start();


    }

    @Override
    public void onDisconnected(ESenseManager eSenseManager) {

        Log.d(TAG, "Disconnected from Device!");
        displayStatus("Disconnected from Device!");

        completeWriting();

    }

    // Methods relevant to SensorChanged event

    @Override
    public void onSensorChanged(ESenseEvent evt) {

        // If we have received the sensor configurations, we save the data.
        if(received_configs) {

            //Log.d(TAG, "PACKET: " + java.util.Arrays.toString(evt.getAccel()));

            //short[] acc = evt.getAccel(); //Acceleration Values
            //short[] gyro = evt.getGyro(); //Rotation values
            double[] acc = evt.convertAccToG(esg);  //Acceleration in g
            double[] gyro = evt.convertGyroToDegPerSecond(esg); // Rotation in degrees/sec
            long timestamp = evt.getTimestamp();  //Get timestamp in system milliseconds.

            num_acc_samples += 1;
            num_gyro_samples += 1;


//            messageToWrite += "\n" + Long.toString(timestamp) + "," + Short.toString(acc[0]) + "," + Short.toString(acc[1]) +
//                    "," + Short.toString(acc[2]) + "," + Short.toString(gyro[0]) + "," + Short.toString(gyro[1]) + "," +
//                    Short.toString(gyro[2]);

            messageToWrite += "\n" + Long.toString(timestamp) + "," + Double.toString(acc[0]) + "," + Double.toString(acc[1]) +
                    "," + Double.toString(acc[2]) + "," + Double.toString(gyro[0]) + "," + Double.toString(gyro[1]) + "," +
                    Double.toString(gyro[2]);

            long current_time = System.currentTimeMillis();

            //Check mean/standard dev of counted transmissions:
            if (last_sampled_time_2 + (total_recording_time*1000) < current_time) {

                last_sampled_time_2 = current_time;

                mean = mean / total_recording_time;

                Log.d(TAG, "Number of ACC samples/sec: " + Long.toString(num_acc_samples));
                Log.d(TAG, "Mean: " + Double.toString(mean) + " Std Dev: " + Double.toString(std_dev));

                mean = 0.0;
                std_dev = 0.0;
            }

            // Check incoming sensor rate:
            if (last_sampled_time + 1000 < current_time) {
                last_sampled_time = current_time;

                Log.d(TAG, "Number of ACC samples/sec: " + Long.toString(num_acc_samples));
                Log.d(TAG, "Number of GYRO samples/sec: " + Long.toString(num_gyro_samples));

                // Format for output is similar to "ACC values: [] Sampling Rate: 0"
                displayACCData("ACC values: " + java.util.Arrays.toString(acc) + " Sampling Rate: " + Long.toString(num_acc_samples));
                displayGYROData("GYRO values: " + java.util.Arrays.toString(gyro) + " Sampling Rate: " + Long.toString(num_gyro_samples));

                //Add to the mean
                mean += num_acc_samples;

                num_acc_samples = 0;
                num_gyro_samples = 0;

                //Write the message to file
                Log.d(TAG, "Writing Message of length to Queue" + Long.toString(messageToWrite.length()));
                writeQueue.add(messageToWrite);
                //dex.directWriteToFile(messageToWrite);
                //Reset the message to nothing.
                messageToWrite = "";
            }
        }

    }

    @Override
    public void onBatteryRead(double v) {

    }

    @Override
    public void onButtonEventChanged(boolean b) {

    }

    @Override
    public void onAdvertisementAndConnectionIntervalRead(int i, int i1, int i2, int i3) {

    }

    @Override
    public void onDeviceNameRead(String s) {
        Log.d(TAG, "READ NAME: " + s);
    }

    @Override
    public void onSensorConfigRead(ESenseConfig eSenseConfig) {
        esg = eSenseConfig;
        received_configs = true;
        Log.d(TAG, "Found Sensor Configurations!");
    }

    @Override
    public void onAccelerometerOffsetRead(int i, int i1, int i2) {

    }
}
