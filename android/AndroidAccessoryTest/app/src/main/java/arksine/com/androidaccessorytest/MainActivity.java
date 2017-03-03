package arksine.com.androidaccessorytest;


import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private EditText mNumberField;
    private TextView mEchoTextView;
    private Handler mUiHandler;
    private boolean mBound = false;
    private AccessoryControlInterface mAccessoryControl;


    private final AccessoryEvents mAccessoryEvents = new AccessoryEvents() {
        @Override
        public void onConnected(final boolean status) throws RemoteException {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (status) {
                        Toast.makeText(MainActivity.this, "Accessory Connected",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Unable to open accessory",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @Override
        public void onDisconnected() throws RemoteException {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Accessory Disconnected",
                            Toast.LENGTH_SHORT).show();
                }
            });

            if (mBound) {
                unbindService(mServiceConnection);
                mBound = false;
            }

            mUiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }, 1000);
        }

        @Override
        public void onDataReceived(final byte[] data) throws RemoteException {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    // TODO: Temporary for testing only
                    if (data.length == 2) {
                        int val = ByteBuffer.wrap(data).getShort() & 0xFFFF;
                        mEchoTextView.append(String.valueOf(val) + '\n');
                    } else {
                        mEchoTextView.append("Data Length Recd: " + data.length + '\n');
                        Thread checkThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                largeDataTest(data);
                            }
                        });
                        checkThread.start();
                    }
                }
            });
        }
    };

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            final AccessoryService.LocalBinder binder = (AccessoryService.LocalBinder) iBinder;

            binder.registerCallback(mAccessoryEvents);
            mAccessoryControl = binder.getControlInterface();

            final String initMsg;
            if (mAccessoryControl == null) {
                initMsg = "Accessory Interface not available";
            } else if (mAccessoryControl.isOpen()) {
                initMsg = "Accessory connected";
            } else {
                initMsg = "Accessory not connected";
            }

            Log.i(TAG, initMsg);
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, initMsg,
                            Toast.LENGTH_SHORT).show();
                }
            });

            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mNumberField = (EditText) findViewById(R.id.edt_number);
        mEchoTextView = (TextView) findViewById(R.id.txt_echo);

        mUiHandler = new Handler(Looper.getMainLooper());

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // This sends exit data to the accessory (currently 0xFFFF signals exit)
                if (mAccessoryControl != null) {
                    // Terminates both this app/service and the python script running on the
                    // accessory
                    mAccessoryControl.closeAccessory(true);
                }
            }
        });

        Button btn = (Button) findViewById(R.id.btn_send);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                short data;
                try {
                    data = Short.valueOf(mNumberField.getText().toString());
                } catch (NumberFormatException e){
                    Log.i("MainActivity", "Number out of bounds");
                    return;
                }

                if (mAccessoryControl != null) {
                    if (mAccessoryControl.writeShort(data)) {
                        Toast.makeText(MainActivity.this, "Writing to Device: " + String.valueOf(data),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Device closed, cannot write",
                                Toast.LENGTH_SHORT).show();
                    }
                }

                mNumberField.setText("");

            }
        });

        if (!isServiceRunning(AccessoryService.class, this)) {
            Intent startIntent = new Intent(this, AccessoryService.class);
            this.startService(startIntent);
        }

    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent bindIntent = new Intent(this, AccessoryService.class);
        bindService(bindIntent, mServiceConnection, Context.BIND_ABOVE_CLIENT);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mBound) {
            unbindService(mServiceConnection);
            mBound = false;
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private static boolean isServiceRunning(Class<?> serviceClass, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    void largeDataTest(byte[] data) {
        boolean valid = true;
        for (int i = 0; i < data.length; i++) {
            if (data[i] != (byte)(i % 256)) {
                valid = false;
                break;
            }
        }

        final String msg = valid ? "Data received and verified accurate" : "Data not valid";
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                mEchoTextView.append(msg + '\n');
                Toast.makeText(MainActivity.this, msg,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
