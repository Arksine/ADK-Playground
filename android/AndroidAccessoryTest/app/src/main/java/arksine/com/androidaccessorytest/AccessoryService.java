package arksine.com.androidaccessorytest;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.hardware.usb.UsbAccessory;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class AccessoryService extends Service {
    private static final String TAG = AccessoryService.class.getSimpleName();

    private NotificationManager mNotificationManager;
    private Notification.Builder mNotificationBuilder;
    private AccessoryManager mAccessoryManager;
    private AtomicBoolean mAttemptingConnect = new AtomicBoolean(false);
    private final IBinder mBinder = new LocalBinder();

    private final RemoteCallbackList<AccessoryEvents> mCallbackList =
            new RemoteCallbackList<>();

    private final BroadcastReceiver mStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(getString(R.string.ACTION_STOP_SERVICE)) ||
                    action.equals(Intent.ACTION_SHUTDOWN)) {
                if (mAccessoryManager != null && mAccessoryManager.isOpen()) {
                    mAccessoryManager.close(false);  // close the service, but not the accessory
                } else {
                    // Even though the accessory is not connected, a disconnect event
                    // should be broadcast so any bound activities know that the service
                    // is shutting down.  This is done to prevent ServiceConnection leaks
                    mEventHandler.sendEmptyMessage(AccessoryEvents.DISCONNECT_EVENT);
                }
            }
        }
    };

    private final Handler.Callback mEventCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case AccessoryEvents.CONNECT_EVENT: {
                    mAttemptingConnect.set(false);
                    boolean connected = (boolean)msg.obj;

                    if (connected) {
                        mNotificationBuilder.setContentText(getText(R.string.NOTIFICATION_CONNECTED));
                        mNotificationManager.notify(R.integer.ONGOING_NOTIFICATION_ID,
                                mNotificationBuilder.build());
                    }

                    int cbCount = mCallbackList.beginBroadcast();
                    for (int i = 0; i < cbCount; i++) {
                        try {
                            mCallbackList.getBroadcastItem(i).onConnected(connected);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    mCallbackList.finishBroadcast();
                    break;
                }
                case AccessoryEvents.DISCONNECT_EVENT: {
                    int cbCount = mCallbackList.beginBroadcast();
                    for (int i = 0; i < cbCount; i++) {
                        try {
                            mCallbackList.getBroadcastItem(i).onDisconnected();
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    mCallbackList.finishBroadcast();
                    // TODO: with more advanced functionality I might need to do more cleanup here

                    // Since the Accessory is discconected I should stop the service, as an
                    // accessory cannot reconnect in the same process
                    Log.d(TAG, "Accessory disconnected, stopping service");
                    stopSelf();
                    break;
                }
                case AccessoryEvents.DATA_EVENT: {
                    int cbCount = mCallbackList.beginBroadcast();
                    for (int i = 0; i < cbCount; i++) {
                        try {
                            mCallbackList.getBroadcastItem(i).onDataReceived((PacketBuffer)msg.obj);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    mCallbackList.finishBroadcast();
                    break;
                }
            }
            return true;
        }
    };
    private Handler mEventHandler;

    @Override
    public IBinder onBind(Intent intent) {

        // Reattempt open if necessary
        /*if (!mAttemptingConnect.get() && !mAccessoryManager.isOpen()) {
            mAttemptingConnect.set(true);
            mAccessoryManager.open();
        }*/

        return mBinder;
    }

    @Override
    public void onCreate() {
        IntentFilter filter = new IntentFilter(getString(R.string.ACTION_STOP_SERVICE));
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mStopReceiver, filter);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // TODO: add priority background to handler thread?
        HandlerThread eventHandlerThread = new HandlerThread("Event Handler Thread");
        eventHandlerThread.start();
        mEventHandler = new Handler(eventHandlerThread.getLooper(), mEventCallback);
        mAccessoryManager = new AccessoryManager(this, mEventHandler);

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

        if (mAccessoryManager.isOpen()) {
            mAccessoryManager.close(false);
        }
        mAccessoryManager.unregisterReceiver();

        unregisterReceiver(mStopReceiver);
        mCallbackList.kill();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(R.integer.ONGOING_NOTIFICATION_ID, mNotificationBuilder.build());

        if (intent == null) {
            Log.i(TAG, "Service received null intent");
        } else {
            Log.i(TAG, "Service received valid intent");
        }
        if (!mAccessoryManager.isOpen()) {
            mAttemptingConnect.set(true);
            mAccessoryManager.open();
        }

        return START_STICKY;
    }

    private final AccessoryControlInterface mControlInterface = new AccessoryControlInterface() {
        @Override
        public void attemptConnect(UsbAccessory accessory) {
            if (!isOpen()) {
                mAccessoryManager.open(accessory);
            }
        }

        @Override
        public boolean isOpen() {
            return mAccessoryManager.isOpen();
        }

        @Override
        public boolean writeBytes(byte[] data) {
            if (this.isOpen()) {
                mAccessoryManager.write(AccessoryCommand.TEST, data);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean writeShort(short data) {
            if (this.isOpen()) {
                byte[] bytes = ByteBuffer.allocate(2).putShort(data).array();
                mAccessoryManager.write(AccessoryCommand.TEST, bytes);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean writeInt(int data) {
            if (this.isOpen()) {
                byte[] bytes = ByteBuffer.allocate(4).putInt(data).array();
                mAccessoryManager.write(AccessoryCommand.TEST, bytes);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean writeString(String data) {
            if (this.isOpen()) {
                mAccessoryManager.write(AccessoryCommand.TEST, data.getBytes());
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void closeAccessory(boolean closeAll) {
            if (this.isOpen()) {
                mAccessoryManager.close(true);
            }
        }
    };

    public class LocalBinder extends Binder {
        public AccessoryControlInterface getControlInterface() {
            return mControlInterface;
        }

        public void registerCallback(AccessoryEvents events) {
            if (events != null) {
                mCallbackList.register(events);
            }
        }

        // Included for functional purposes, but it isn't really necessary.  Attached
        // processed that die are automatically removed from the callback list
        public void unregisterCallback(AccessoryEvents events) {
            if (events != null) {
                mCallbackList.unregister(events);
            }
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
