package com.arksine.adbtest;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();

    private EditText mTestField;
    private SurfaceView mCameraView;
    private SurfaceHolder mCameraHolder;
    private BitmapFactory.Options mBitOptions;
    private Bitmap mCameraBitmap;
    private Rect mCameraWindow;
    private Handler mCanvasHandler;
    private Handler mUiHandler;
    private boolean mBound = false;
    private boolean mCameraOn = false;
    private MageControlInterface mMageControl;

    private final MageEvents mMageEvents = new MageEvents() {
        @Override
        public void onConnected(final boolean status) throws RemoteException {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (status) {
                        Toast.makeText(MainActivity.this, "SocketIO Connected",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Unable to open SocketIO connection",
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
                    Toast.makeText(MainActivity.this, "SocketIO Disconnected",
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
        public void onTest(final String data) throws RemoteException {
            Toast.makeText(MainActivity.this, "Received Value: " +  data,
                    Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onFrameReceived(final byte[] frame) throws RemoteException {
            Message msg = mCanvasHandler.obtainMessage();
            msg.obj = frame;
            mCanvasHandler.sendMessage(msg);
        }

        @Override
        public void onLogEvent(final String logInfo) throws RemoteException {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    Snackbar.make(findViewById(R.id.main_layout), logInfo,
                            Snackbar.LENGTH_SHORT).show();
                }
            });
        }
    };

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            final MainService.LocalBinder binder = (MainService.LocalBinder) iBinder;

            binder.registerCallback(mMageEvents);
            mMageControl = binder.getControlInterface();

            final String initMsg;
            if (mMageControl == null) {
                initMsg = "Mage Interface not available";
            } else if (mMageControl.isConnected()) {
                initMsg = "SocketIO connected";
            } else {
                initMsg = "SocketIO not connected";
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

        mTestField = (EditText) findViewById(R.id.edt_test);

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
                byte[] buf = (byte[])msg.obj;

                mCameraBitmap = BitmapFactory.decodeByteArray(buf, 0,
                        buf.length, mBitOptions);
                Canvas canvas = mCameraHolder.lockCanvas();
                if (canvas != null) {
                    canvas.drawBitmap(mCameraBitmap, null, mCameraWindow, null);
                }
                mCameraHolder.unlockCanvasAndPost(canvas);

                return true;
            }
        };

        mCanvasHandler = new Handler(canvasThread.getLooper(), canvasCallback);
        mUiHandler = new Handler(Looper.getMainLooper());

        Button btn = (Button) findViewById(R.id.btn_send);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String data;
                try {
                    data = mTestField.getText().toString();
                } catch (NumberFormatException e){
                    Log.i("MainActivity", "Number out of bounds");
                    return;
                }

                if (mMageControl != null) {
                    mMageControl.sendTestCommand(MageCommand.TEST, data);
                    Toast.makeText(MainActivity.this, "Writing to Device: " + data,
                            Toast.LENGTH_SHORT).show();

                }

                mTestField.setText("");

            }
        });

        Button camBtn = (Button) findViewById(R.id.btn_camera);
        camBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCameraOn) {
                    mMageControl.sendTestCommand(MageCommand.CAM_STOP, null);
                } else {
                    mMageControl.sendTestCommand(MageCommand.CAM_START, null);
                }
                mCameraOn = !mCameraOn;
            }
        });

        if (!isServiceRunning(MainService.class, this)) {
            Intent startIntent = new Intent(this, MainService.class);
            this.startService(startIntent);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent bindIntent = new Intent(this, MainService.class);
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

    private static boolean isServiceRunning(Class<?> serviceClass, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
