package com.arksine.adbtest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager for simple Usb Accessory comms
 */

/**
 * TODO: Current implementation seems to work, but I had a random crash that I havent been
 *  able to duplicate.  I need to test longer to see if the crash is on the python side
 *  or on the android side.
 *
 *  The task now is to decouple the "Accessory Bridge" from the test app, and turn it in
 *  to a library.  The same should be done on the linux/python side.  Would be nice to get
 *  it working on windows, but libusb just hasn't caught up, seems to lack too many
 *  features (reset for example).
 *
 *  The future Service/Library should be able to forward multiple connections.  This means
 *  I need to Mux/Demux connection payloads so I know what socket to forward data to.
 *
 *  On the python side, it should listen for connections, and
 *  when it finds what I am looking for it can fire up the Accessory bridge process
 */
class AccessoryBridge {
    private static final String TAG = AccessoryBridge.class.getSimpleName();
    private static final boolean DEBUG = true;

    private static final String MANUFACTURER = "Arksine";
    private static final String MODEL = "AccesoryTest";
    private static final String ACTION_USB_PERMISSION = "com.arksine.adbtest.USB_PERMISSION";
    private static final byte[] CMD_ACCESSORY_CONNECTED = {(byte) 0x01, (byte) 0x01};
    private static final byte[] CMD_START_CONNECTION = {(byte) 0x02, (byte) 0x02};
    private static final short CMD_EXIT = 15;

    interface Callbacks {
        void onAccessoryConnected(boolean connected);
        void onSocketConnected(boolean connected);
        void onError(String error);
        void onClose();
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (accessory != null) {
                            openAccessory(accessory);
                            return;
                        }
                    }

                    Log.d(TAG, "Accessory permission not granted.");
                    AccessoryBridge.this.mAccessoryCallbacks.onAccessoryConnected(false);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (AccessoryBridge.this.isValidAccessory(accessory)) {
                    AccessoryBridge.this.open(accessory);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (accessory != null && accessory.equals(mAccessory)){
                    AccessoryBridge.this.close();
                }
            }
        }
    };

    private Context mContext;
    private Callbacks mAccessoryCallbacks;
    private UsbManager mUsbManger;
    private AtomicBoolean mAccessoryConnected = new AtomicBoolean(false);
    private AtomicBoolean mUsbReceiverRegistered = new AtomicBoolean(false);
    private AtomicBoolean mSocketConnected = new AtomicBoolean(false);

    private UsbAccessory mAccessory;
    private ParcelFileDescriptor mFileDescriptor = null;
    private FileInputStream mAccessoryInputStream = null;
    private FileOutputStream mAccessoryOutputStream = null;

    private volatile ServerSocket mServerSocket = null;
    private volatile Socket mServerConnection = null;
    private volatile InputStream mSocketInputStream = null;
    private volatile OutputStream mSocketOutputStream = null;

    private Thread mAccessoryReadThread = null;
    private Thread mSocketReadThread = null;
    private Thread mConnectionListenerThread = null ;

    AccessoryBridge(Context context, Callbacks accCbs) {
        this.mContext = context;
        this.mAccessoryCallbacks = accCbs;
        this.mUsbManger = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

        registerReceiver();
    }

    private void registerReceiver() {
        //  register main usb receiver
        if (mUsbReceiverRegistered.compareAndSet(false, true)) {
            IntentFilter usbFilter = new IntentFilter();
            usbFilter.addAction(ACTION_USB_PERMISSION);
            usbFilter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
            usbFilter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
            mContext.registerReceiver(mUsbReceiver, usbFilter);
        }
    }

                                                                                                                                                                                                        void unregisterReceiver() {
        if (mUsbReceiverRegistered.compareAndSet(true, false)) {
            mContext.unregisterReceiver(mUsbReceiver);
        }
    }

    void open() {
        this.open(null);
    }


    void open(UsbAccessory acc) {

        if (this.mAccessoryConnected.get()) {
            if (this.mSocketConnected.get()) {
                Log.w(TAG, "Accessory already connected");
                return;
            } else {
                // check to see if listener thread is running, if not start it
                if (mConnectionListenerThread != null && mConnectionListenerThread.isAlive()) {
                    mAccessoryCallbacks.onAccessoryConnected(true);
                } else {
                    mConnectionListenerThread = new Thread(mConnectionListener);
                    mConnectionListenerThread.start();
                    mAccessoryCallbacks.onAccessoryConnected(true);
                }
                return;
            }
        }

        // No accessory was passed to the activity via intent, so attempt to detect it
        if (acc == null) {
            acc = detectAccessory();
            if (acc == null) {
                Log.d(TAG, "Unable to detect accessory.");
                mAccessoryCallbacks.onAccessoryConnected(false);
                return;
            }
        }

        if (mUsbManger.hasPermission(acc)) {
            openAccessory(acc);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0,
                    new Intent(ACTION_USB_PERMISSION), 0);
            mUsbManger.requestPermission(acc, pi);
        }

    }

    public boolean isOpen() {
        return mAccessoryConnected.get();
    }

    void close() {

        // the stop reading function can block, so close in a new thread to prevent
        // from blocking UI thread
        Thread closeThread = new Thread(mCloseRunnable);
        closeThread.start();

    }


    private boolean isValidAccessory(UsbAccessory acc) {
        if (acc != null) {
            if (MANUFACTURER.equals(acc.getManufacturer()) &&
                    MODEL.equals(acc.getModel())) {
                return true;
            }
        }

        return false;
    }

    private UsbAccessory detectAccessory() {
        UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        UsbAccessory[] accessoryList = usbManager.getAccessoryList();

        if (accessoryList != null && !(accessoryList.length == 0)) {
            UsbAccessory accessory = accessoryList[0];
            if (isValidAccessory(accessory)) {
                return accessory;

            } else {
                Log.e(TAG, "Connected Accessory is not a match for expected accessory\n" +
                        "Expected Accessory: " + MANUFACTURER + ":" + MODEL + "\n" +
                        "Connected Accessory: " + accessory.getManufacturer() + ":" +
                        accessory.getModel());
            }
        }

        Log.i(TAG, "Accessory not found");
        return null;
    }

    private void closeItem(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void stopThread(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(100);
            } catch (InterruptedException e) {
                if (DEBUG)
                    e.printStackTrace();
            } finally {
                if (thread.isAlive())
                    thread.interrupt();
            }
        }
    }


    private void openAccessory(UsbAccessory accessory) {
        mAccessory = accessory;
        mFileDescriptor = mUsbManger.openAccessory(mAccessory);

        if (mFileDescriptor != null) {
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mAccessoryOutputStream = new FileOutputStream(fd);
            mAccessoryInputStream = new FileInputStream(fd);
            try {
                mAccessoryOutputStream.write(CMD_ACCESSORY_CONNECTED);
            } catch (IOException e) {
                mAccessoryCallbacks.onAccessoryConnected(false);
                close();
                return;
            }
            mAccessoryConnected.set(true);
            mAccessoryReadThread = new Thread(null, mAccessoryReadRunnable, "Accessory Read Thread");
            mAccessoryReadThread.start();
            mConnectionListenerThread = new Thread(null, mConnectionListener, "Connection Listener Thread");
            mConnectionListenerThread.start();
            mAccessoryCallbacks.onAccessoryConnected(true);

        } else {
            Log.d(TAG, "Unable to open Accessory File Descriptor");
            mAccessoryCallbacks.onAccessoryConnected(false);
        }
    }

    private final Runnable mAccessoryReadRunnable = new Runnable() {
        @Override
        public void run() {
            int bytesRead;
            byte[] inputBuffer = new byte[16384];

            while (mAccessoryConnected.get()) {
                try {
                    bytesRead = mAccessoryInputStream.read(inputBuffer);
                } catch (IOException e) {
                    break;
                }

                if (bytesRead > 0) {
                    if (mServerConnection.isConnected()) {
                        try {
                            mSocketOutputStream.write(inputBuffer, 0, bytesRead);
                            mSocketOutputStream.flush();
                        } catch (IOException e) {
                            break;
                        }
                    } else if (bytesRead == 2) {
                        // If the socket is not connected listen for a disconnect
                        // command
                        short cmd = ByteBuffer.wrap(inputBuffer).getShort();
                        if (cmd == CMD_EXIT) {
                            break;
                        }
                    }
                }
            }

            if (mAccessoryConnected.get()) {
                // Accessory disconnected, either due to error or socket disconnection
                close();
            }
        }
    };

    private final Runnable mSocketReadRunnable = new Runnable() {
        @Override
        public void run() {
            int bytesRead;
            byte[] inputBuffer = new byte[16384];

            while (mServerConnection.isConnected()) {
                try {
                    bytesRead = mSocketInputStream.read(inputBuffer);
                } catch (IOException e) {
                    break;
                }

                if (bytesRead > 0 && mAccessoryConnected.get()) {
                    try {
                        mAccessoryOutputStream.write(inputBuffer, 0, bytesRead);
                    } catch (IOException e) {
                        break;
                    }
                }
            }

            if (mSocketConnected.get()) {
                mAccessoryCallbacks.onError("Socket Disconnected");
            }

        }
    };

    private final Runnable mConnectionListener = new Runnable() {
        @Override
        public void run() {
            try {
                // TODO: probably don't need to bind to all interfaces, only localhost
                mServerSocket = new ServerSocket(8000);
                mServerConnection = mServerSocket.accept();
                mSocketInputStream = mServerConnection.getInputStream();
                mSocketOutputStream = mServerConnection.getOutputStream();
                mSocketConnected.set(true);
                mAccessoryOutputStream.write(CMD_START_CONNECTION);  // Notify the usb connection
                mSocketReadThread = new Thread(null, mSocketReadRunnable, "Socket Read Thread");
                mSocketReadThread.start();
                mAccessoryCallbacks.onSocketConnected(true);
            } catch (IOException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                mAccessoryCallbacks.onSocketConnected(false);
            }
        }
    };

    private final Runnable mCloseRunnable = new Runnable() {

        @Override
        public void run() {
            // Attempt to close socket items
            mSocketConnected.set(false);
            closeItem(mServerSocket);
            closeItem(mServerConnection);
            closeItem(mSocketInputStream);
            closeItem(mSocketOutputStream);

            // Stop socket threads
            stopThread(mConnectionListenerThread);
            stopThread(mSocketReadThread);


            if (mAccessoryConnected.compareAndSet(true, false)) {
                // TODO: send terminate command
                stopThread(mAccessoryReadThread);
                closeItem(mAccessoryInputStream);
                closeItem(mAccessoryOutputStream);
                closeItem(mFileDescriptor);

            }

            mServerSocket = null;
            mServerConnection = null;
            mSocketInputStream = null;
            mSocketOutputStream = null;
            mAccessoryInputStream = null;
            mAccessoryOutputStream = null;
            mFileDescriptor = null;
            mConnectionListenerThread = null;
            mSocketReadThread = null;
            mAccessoryReadThread = null;

            mAccessoryCallbacks.onClose();
        }
    };


}
