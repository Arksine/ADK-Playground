package arksine.com.androidaccessorytest;


import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;


public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();

    private EditText mNumberField;
    private SurfaceView mCameraView;
    private SurfaceHolder mCameraHolder;
    private BitmapFactory.Options mBitOptions;
    private Bitmap mCameraBitmap;
    private Rect mCameraWindow;
    private Handler mCanvasHandler;
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
        public void onDataReceived(final PacketBuffer data) throws RemoteException {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    // TODO: Temporary for testing only
                    if (data.getPayloadSize() == 2) {
                        int val = data.getPayloadBuffer().getShort() & 0xFFFF;

                        Toast.makeText(MainActivity.this, "Received Value: " +  val,
                                Toast.LENGTH_SHORT).show();

                        // Done with the buffer, so clear it and return to queue
                        data.clear();
                    } else {
                        // Limit (size) is not 2, so during testing I know
                        // it is a camera frame.  Send it to the canvas
                        // handler for rendering
                        Message msg = mCanvasHandler.obtainMessage();
                        msg.obj = data;
                        mCanvasHandler.sendMessage(msg);
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

        // Surface, Drawing vars
        mCameraView = (SurfaceView) findViewById(R.id.camera_view);
        mCameraHolder = mCameraView.getHolder();
        mCameraHolder.addCallback(this);

        Bitmap reusable = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        mBitOptions = new BitmapFactory.Options();
        mBitOptions.inBitmap = reusable;
        mBitOptions.outWidth = 640;
        mBitOptions.outHeight = 480;

        HandlerThread canvasThread = new HandlerThread("Canvas thread",
                Process.THREAD_PRIORITY_DISPLAY);
        canvasThread.start();
        Handler.Callback canvasCallback = new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                PacketBuffer buf = (PacketBuffer)msg.obj;

                mCameraBitmap = BitmapFactory.decodeByteArray(buf.getArray(), buf.payloadStartIndex(),
                        buf.getPayloadSize(), mBitOptions);
                Canvas canvas = mCameraHolder.lockCanvas();
                if (canvas != null) {
                    canvas.drawBitmap(mCameraBitmap, null, mCameraWindow, null);
                }
                mCameraHolder.unlockCanvasAndPost(canvas);

                // we are done with the data buffer, so clear it and return it to the queue
                buf.clear();
                return true;
            }
        };
        mCanvasHandler = new Handler(canvasThread.getLooper(), canvasCallback);


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
                Toast.makeText(MainActivity.this, msg,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int winWidth, int winHeight) {
        // Update the view window, displays a 4:3 image and fills either width or height,
        // depending on orientation
        Log.d("WebCam", "surfaceChanged");
        int width, height, dw, dh;
        if(winWidth * 3 / 4 <= winHeight) {
            dw = 0;
            dh = (winHeight - winWidth * 3 / 4) / 2;
            width = dw + winWidth - 1;
            height = dh + winWidth * 3 / 4 - 1;
        } else {
            dw = (winWidth - winHeight * 4 / 3) / 2;
            dh = 0;
            width = dw + winHeight * 4 / 3 - 1;
            height = dh + winHeight - 1;
        }
        mCameraWindow = new Rect(dw, dh, width, height);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // TODO:
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // TODO:
    }
}
