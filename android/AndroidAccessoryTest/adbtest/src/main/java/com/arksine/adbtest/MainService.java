package com.arksine.adbtest;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.util.Log;

public class MainService extends Service {
    private static final String TAG = MainService.class.getSimpleName();
    private static final String DEFAULT_URI = "127.0.0.1:8000";

    private final IBinder mBinder = new LocalBinder();
    private NotificationManager mNotificationManager;
    private Notification.Builder mNotificationBuilder;

    private SioManager mSioManager = null;
    private AccessoryManager mAccessoryManager = null;
    private final EventManager<MageEvents> mMageEvents = new EventManager<>();
    private EventHandler mEventHandler;

    private final BroadcastReceiver mStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(getString(R.string.ACTION_STOP_SERVICE)) ||
                    action.equals(Intent.ACTION_SHUTDOWN)) {
                if (mSioManager.isConnected()) {
                    mSioManager.disconnect();
                } else {
                    // We still need to disconnect to stop the socket from attempting
                    // reconnections, but we need to fire the event to bound activities
                    // manually
                    mSioManager.disconnect();
                    mEventHandler.sendEmptyMessage(MageEvents.DISCONNECT_EVENT);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(getString(R.string.ACTION_STOP_SERVICE));
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mStopReceiver, filter);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // TODO: add priority background to handler thread?
        HandlerThread eventHandlerThread = new HandlerThread("Event Handler Thread");
        eventHandlerThread.start();
        mEventHandler = new EventHandler(eventHandlerThread.getLooper(), mMageEvents,
                mEventHandlerCbs);

        Bitmap largeIcon = getLargeNotificationIcon();
        Intent stopIntent = new Intent(getString(R.string.ACTION_STOP_SERVICE));
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this, R.integer.REQUEST_STOP_SERVICE,
                stopIntent, 0);
        // TODO: Lanches same activity multiple times, Bad!
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                R.integer.REQUEST_START_MAIN_ACITIVITY, notificationIntent, 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_stop);
            Notification.Action stopAction = new Notification.Action.Builder(stopIcon,
                    "Stop Service", stopPendingIntent).build();
            mNotificationBuilder = new Notification.Builder(this)
                    .setContentTitle(getText(R.string.service_name))
                    .setContentText(getText((R.string.NOTIFICATION_NOT_CONNECTED)))
                    .setSmallIcon(R.drawable.ic_notification_small)
                    .setLargeIcon(largeIcon)
                    .setContentIntent(pendingIntent)
                    .addAction(stopAction)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH);

        } else {
            mNotificationBuilder = new Notification.Builder(this)
                    .setContentTitle(getText(R.string.service_name))
                    .setContentText(getText(R.string.NOTIFICATION_NOT_CONNECTED))
                    .setSmallIcon(R.drawable.ic_notification_small)
                    .setLargeIcon(largeIcon)
                    .setContentIntent(pendingIntent)
                    .addAction(R.drawable.ic_stop,
                            "Stop Service", stopPendingIntent)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mSioManager.isConnected()) {
            mSioManager.disconnect();
        }
        unregisterReceiver(mStopReceiver);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // TODO: can I check for adb and/or wifi connection first?
        // TODO: Check preferences, determine if Accessory mode should be used.  If so,
        // attempt to set up the accessory and forward the port before setting up the
        // SioManager.  IF I can't forward the port though simple Sockets, I'll attempt to
        // set up a VPNService and connect the a Linux TUN/TAP on the other end

        startForeground(R.integer.ONGOING_NOTIFICATION_ID, mNotificationBuilder.build());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: check connection and attempt to connect?
        return mBinder;
    }

    private void setupConnection() {
        SharedPreferences globalPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (mSioManager != null) {
            mSioManager.disconnect();
        }

        boolean useWifi = globalPrefs.getBoolean("pref_key_use_wifi", true);
        if (useWifi) {
            String uri = globalPrefs.getString("pref_key_socketio_addr", DEFAULT_URI);
            mSioManager = new SioManager(uri, mEventHandler);
            mSioManager.connect();
        } else {
            mSioManager = new SioManager(DEFAULT_URI, mEventHandler);
            mAccessoryManager = new AccessoryManager(this, mAccessoryCallbacks);
            mAccessoryManager.open();
        }

    }


    private final EventHandler.ServiceCallbacks mEventHandlerCbs = new EventHandler.ServiceCallbacks() {
        @Override
        public void updateNotification(boolean connected) {
            if (connected) {
                mNotificationBuilder.setContentText(getText(R.string.NOTIFICATION_CONNECTED));
            } else {
                mNotificationBuilder.setContentText(getText(R.string.NOTIFICATION_NOT_CONNECTED));
            }
            mNotificationManager.notify(R.integer.ONGOING_NOTIFICATION_ID,
                    mNotificationBuilder.build());
        }

        @Override
        public void stopService() {
            stopSelf();
        }
    };

    private final AccessoryManager.Callbacks mAccessoryCallbacks = new AccessoryManager.Callbacks() {
        @Override
        public void onAccessoryConnected(boolean connected) {
            if (connected) {
                if (mSioManager != null) {
                    mSioManager.connect();
                }
            } else {
                Log.i(TAG, "Error Connecting to Accessory");
                Message msg = mEventHandler.obtainMessage(MageEvents.LOG_EVENT,
                        "Error Connecting to Accessory");
                mEventHandler.sendMessage(msg);
            }
        }

        @Override
        public void onSocketConnected(boolean connected) {
            Log.i(TAG, "Accessory/Socket Connected");
        }

        @Override
        public void onError(String error) {
            Log.e(TAG, error);
            Message msg = mEventHandler.obtainMessage(MageEvents.LOG_EVENT, error);
            mEventHandler.sendMessage(msg);
        }

        @Override
        public void onClose() {
            // TODO: Accessory Closed
        }
    };

    // TODO:  implement the interface below
    private final MageControlInterface mControlInterface = new MageControlInterface() {
        @Override
        public boolean isConnected() {
            return mSioManager.isConnected();
        }

        @Override
        public void sendTestCommand(MageCommand cmd, String data) {
            mSioManager.sendCommand(cmd, data);
        }

        @Override
        public void disconnect() {
            mSioManager.disconnect();
        }

        @Override
        public void refreshConnection() {
            setupConnection();
        }
    };

    public class LocalBinder extends Binder {
        public MageControlInterface getControlInterface() {
            return mControlInterface;
        }

        public void registerCallback(MageEvents events) {
            if (events != null) {
                mMageEvents.registerCallback(events);
            }
        }

        // Included for functional purposes, but it isn't really necessary.  Attached
        // processed that die are automatically removed from the callback list
        public void unregisterCallback() {
            mMageEvents.unregisterCallback();
        }
    }

    private Bitmap getLargeNotificationIcon() {
        Bitmap icon = BitmapFactory.decodeResource(getResources(),
                R.drawable.notification_large);

        float scaleMultiplier = getResources().getDisplayMetrics().density / 3f;

        return Bitmap.createScaledBitmap(icon, (int)(icon.getWidth() * scaleMultiplier),
                (int)(icon.getHeight() * scaleMultiplier), false);
    }
}
