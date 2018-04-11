package com.example.btft;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity
{
    TextView labelView, timeView, vinView, rpmView, speedView, odometerView, latView, longView, headingView, engineHoursView;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    volatile boolean stopWorker;
    String esnNumber = "";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        labelView = findViewById(R.id.labelView);
        timeView = findViewById(R.id.timeView);
        vinView = findViewById(R.id.vinView);
        rpmView = findViewById(R.id.rpmView);
        speedView = findViewById(R.id.speedView);
        odometerView = findViewById(R.id.odometerView);
        latView = findViewById(R.id.latView);
        longView = findViewById(R.id.longView);
        headingView = findViewById(R.id.headingView);
        engineHoursView = findViewById(R.id.engineHoursView);
    }

    void findBT()
    {
        String esnBluetoothId = "LMU_" + esnNumber;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        Boolean btFound = false;

        if (mBluetoothAdapter == null)
        {
            labelView.setText(R.string.no_bt_avail);
        }

        if (!mBluetoothAdapter.isEnabled())
        {
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, 0);
        }

        try
        {
            Thread.sleep(3000);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0)
        {
            for (BluetoothDevice device : pairedDevices)
            {
                if (device.getName().equals(esnBluetoothId))
                {
                    mmDevice = device;
                    btFound = true;
                    break;
                }
            }
        }

        labelView.setText(btFound ? R.string.bt_found : R.string.bt_not_found);
    }

    void openBT() throws IOException
    {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID

        if (mmDevice == null)
        {
            Toast toast = Toast.makeText(this, "Invalid FT.", Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        }
        else
        {
            mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
            mmSocket.connect();
            mmOutputStream = mmSocket.getOutputStream();
            mmInputStream = mmSocket.getInputStream();

            beginListenForData();

            labelView.setText(R.string.bt_opened);
        }
    }

    void beginListenForData()
    {
        final Handler handler = new Handler();
        final byte delimiter = 10; //This is the ASCII code for a newline character

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];

        workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while (!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        int bytesAvailable = mmInputStream.available();

                        if (bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            mmInputStream.read(packetBytes);

                            for (int i = 0; i < bytesAvailable; i++)
                            {
                                byte b = packetBytes[i];

                                if (b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String[] dataStrings = tokenize(new String(encodedBytes, "US-ASCII"));
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            /* Common to all messages*/
                                            labelView.setText(dataStrings[0]);
                                            timeView.setText(dataStrings[1]);
                                            vinView.setText(dataStrings[2]);

                                            switch (dataStrings[0])
                                            {
                                                case "IGON":
                                                case "IGOFF":
                                                    break;
                                                case "GPS":
                                                    latView.setText(dataStrings[3]);
                                                    longView.setText(dataStrings[4]);
                                                    headingView.setText(dataStrings[5]);
                                                    break;
                                                case "MV0":
                                                    speedView.setText(dataStrings[3]);
                                                    odometerView.setText(dataStrings[4]);
                                                    break;
                                                case "EN0":
                                                    rpmView.setText(dataStrings[3]);
                                                    engineHoursView.setText(dataStrings[4]);
                                                    break;
                                            }
                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    private String[] tokenize(String str)
    {
        String data = str.substring(0, str.length() - 2);
        String[] tokens = data.split(";");
        String[] statusTokens = tokens[0].split(":");

        /* Commom to all five messages */
        tokens[0] = statusTokens[1];

        String[] timeTokens = tokens[1].split(":");
        Long date = Long.parseLong(timeTokens[1]) * 1000;
        tokens[1] = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date(date));

        String[] vinTokens = tokens[2].split(":");
        tokens[2] = vinTokens[1];
        /*     *****************       */

        switch (statusTokens[1])
        {
            case "IGON":
            case "IGOFF":
                break;
            case "GPS":
                String[] latTokens = tokens[3].split(":");
                tokens[3] = latTokens[1];

                String[] longTokens = tokens[4].split(":");
                tokens[4] = longTokens[1];

                String[] headingTokens = tokens[5].split(":");
                tokens[5] = headingTokens[1];
                break;
            case "MV0":
                String[] speedTokens = tokens[3].split(":");
                double speed = Double.parseDouble(speedTokens[1]);
                speed = speed * 0.000006213711922 * 3600; // cm/s to m.p.h.
                tokens[3] = String.format(Locale.US, "%.2f", speed);

                String[] odometerTokens = tokens[4].split(":");
                double odometer = Double.parseDouble(odometerTokens[1]);
                odometer *= 0.000621371; // meters to miles
                tokens[4] = String.format(Locale.US, "%.1f", odometer);
                break;
            case "EN0":
                String[] rpmTokens = tokens[3].split(":");
                Integer rpm = Integer.parseInt(rpmTokens[1]);
                rpm /= 10;
                tokens[3] = rpm.toString();

                String[] engineHoursTokens = tokens[4].split(":");
                tokens[4] = engineHoursTokens[1];
                break;
        }

        return tokens;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings)
        {
            if (esnNumber.isEmpty())
            {
                Toast toast = Toast.makeText(this, "Please enter an FT number before attempting to connect.", Toast.LENGTH_LONG);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                return false;
            }
            else
            {
                try
                {
                    findBT();
                    openBT();
                }
                catch (IOException ex)
                {
                    // intentionally blank
                }
            }

            return true;
        }
        else if (id == R.id.ft_settings)
        {
            AlertDialog.Builder dialog = new AlertDialog.Builder(this, R.style.Theme_AppCompat_Dialog_Alert);
            dialog.setTitle("ESN Number");
            final EditText esnText = new EditText(this);

            esnText.setInputType(InputType.TYPE_CLASS_NUMBER);
            esnText.setTextColor(Color.WHITE);
            dialog.setView(esnText);

            dialog.setPositiveButton("OK", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface dialog, int which)
                {
                    esnNumber = esnText.getText().toString();
                }
            });

            dialog.setNegativeButton("Cancel", new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface dialog, int which)
                {
                    dialog.cancel();
                }
            });

            dialog.show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
