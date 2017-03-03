package arksine.com.androidaccessorytest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager for simple Usb Accessory comms
 */

class AccessoryManager implements Runnable {
    private static final String TAG = AccessoryManager.class.getSimpleName();

    private static final int PACKET_HEADER_SIZE = 2;  // TODO: this can change (currently the header only contains a short that indicates packet length)
    private static final byte[] EXIT_BYTES = {(byte)0xFF, (byte)0xFF};
    private static final byte[] TERMINATE_BYTES = {(byte)0xFF, (byte)0xFE};
    private static final byte[] CONNECTED_BYTES = {(byte)0xFF, (byte)0xFD};

    private static final String MANUFACTURER = "Arksine";
    private static final String MODEL = "AccesoryTest";
    private static final String ACTION_USB_PERMISSION = "com.arksine.accessorytest.USB_PERMISSION";
    private static final int READ_MSG = 0;
    private static final int WRITE_MSG = 1;

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
                    Message msg = mEventHandler.obtainMessage(AccessoryEvents.CONNECT_EVENT, false);
                    mEventHandler.sendMessage(msg);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (AccessoryManager.this.isValidAccessory(accessory)) {
                    AccessoryManager.this.open(accessory);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (accessory != null && accessory.equals(mAccessory)){
                    AccessoryManager.this.close(false);
                }
            }
        }
    };

    private Context mContext;
    private UsbManager mUsbManger;
    private Handler mEventHandler;
    private AtomicBoolean mAccessoryConnected = new AtomicBoolean(false);
    private AtomicBoolean mUsbReceiverRegistered = new AtomicBoolean(false);
    private AtomicBoolean mIsReading = new AtomicBoolean(false);

    private UsbAccessory mAccessory;
    private ParcelFileDescriptor mFileDescriptor;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;
    private Thread mReadThread;

    private Handler mWriteHandler;


    AccessoryManager(Context context, Handler eventHandler) {
        this.mContext = context;
        this.mEventHandler = eventHandler;
        this.mUsbManger = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

        HandlerThread handlerThread = new HandlerThread("Write Handler",
                Process.THREAD_PRIORITY_DEFAULT);
        handlerThread.start();
        Handler.Callback handlerCb = new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                byte[] data = (byte[])msg.obj;
                if (mOutputStream != null) {
                    try {
                        // TODO, probably a faster way to get size bytes
                        short size = (short)data.length;
                        byte[] size_bytes = ByteBuffer.allocate(2).putShort(size).array();
                        mOutputStream.write(size_bytes);
                        mOutputStream.write(data);
                        mOutputStream.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return true;
            }
        };
        mWriteHandler = new Handler(handlerThread.getLooper(), handlerCb);

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
            Log.w(TAG, "Accessory already connected");
            return;
        }

        // No accessory was passed to the activity via intent, so attempt to detect it
        if (acc == null) {
            acc = detectAccessory();
            if (acc == null) {
                Message msg = mEventHandler.obtainMessage(AccessoryEvents.CONNECT_EVENT, false);
                mEventHandler.sendMessage(msg);
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

    void close(final boolean terminateAccessory) {
        if (mAccessoryConnected.compareAndSet(true, false)) {

            // the stop reading function can block, so close in a new thread to prevent
            // from blocking UI thread
            Thread closeThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    // Send a request to disconnect
                    if (mIsReading.get()) {
                        if (terminateAccessory) {
                            write(TERMINATE_BYTES);
                        } else {
                            write(EXIT_BYTES);
                        }
                    }

                    // wait for the thread to disconnect
                    if (mReadThread != null && mReadThread.isAlive()) {

                        try {
                            mReadThread.join(5000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            if (mReadThread.isAlive()) {
                                Log.i(TAG, "Read thread stuck, attempting to interrupt");
                                mReadThread.interrupt();
                            }
                        }
                    }

                    closeItem(mInputStream);
                    closeItem(mOutputStream);
                    closeItem(mFileDescriptor);

                    mReadThread = null;
                    mInputStream = null;
                    mOutputStream = null;
                    mFileDescriptor = null;

                    mEventHandler.sendEmptyMessage(AccessoryEvents.DISCONNECT_EVENT);
                }
            });
            closeThread.start();
        } else {
            Log.i(TAG, "Accessory not connected");
        }
    }

    void write(final byte[] data) {
        Message msg = mWriteHandler.obtainMessage();
        msg.obj = data;
        mWriteHandler.sendMessage(msg);
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

    @Override
    public void run() {
        mIsReading.set(true);
        int bytesRead;
        boolean isHeader = true;
        int size = PACKET_HEADER_SIZE;  // header is a short (FOR NOW)
        byte[] buffer = new byte[16384];
        ByteBuffer packet = ByteBuffer.allocateDirect(32768);

        // TODO: there is probably a better way of doing this without all of the copies.
        // just read the number of bytes you are looking for, then check.  If the number
        // of bytes received is less than requested, store it in a buffer and get the
        // remaining bytes.  If the amount received is equal to the amount requested I can
        // process directly on that buffer. (I can have an arraylist of buffers, not sure how many I need,
        // say 10.  I keep track of how many are in use, if all of them are in use I have to wait
        // until one is freed, or I can create a new buffer and append it to the list

        outerloop:
        while (mIsReading.get()) {
            try {
                bytesRead = mInputStream.read(buffer);
            } catch (IOException e) {
                mIsReading.set(false);
                break;
            }

            if (bytesRead > 0) {
                packet.put(buffer,0, bytesRead);
                if (packet.position() >= size) {
                    packet.flip();

                    while (packet.remaining() >= size) {
                        if (isHeader) {
                            size = packet.getShort() & 0xFFFF;
                            isHeader = false;
                            Log.i(TAG, "Header size: " + String.valueOf(size));
                            // Exit signal received
                            if (size == 0xFFFF) {
                                Log.i(TAG, "Exit recieved");
                                mIsReading.set(false);
                                break outerloop;
                            }
                        } else {
                            byte[] data = new byte[size];
                            packet.get(data);
                            // Send data for processing
                            Message msg = mEventHandler.obtainMessage(AccessoryEvents.DATA_EVENT,
                                    data);
                            mEventHandler.sendMessage(msg);

                            isHeader = true;
                            size = PACKET_HEADER_SIZE;
                        }
                    }

                    if (packet.hasRemaining()) {
                        // Shift remaining bytes to beginning of packet
                        byte[] rem = new byte[packet.remaining()];
                        packet.get(rem);
                        packet.clear();
                        packet.put(rem);
                    } else {
                        packet.clear();
                    }
                }
            }
        }

        if (mAccessoryConnected.get()) {
            close(false);
        }
    }

    private void openAccessory(UsbAccessory accessory) {
        mAccessory = accessory;
        mFileDescriptor = mUsbManger.openAccessory(mAccessory);

        if (mFileDescriptor != null) {
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mOutputStream = new FileOutputStream(fd);
            mInputStream = new FileInputStream(fd);
            mAccessoryConnected.set(true);
            mReadThread = new Thread(null, this, "Accessory Read Thread");
            mReadThread.start();
            this.write(CONNECTED_BYTES);   // Tell the accessory we are connected
            Message msg = mEventHandler.obtainMessage(AccessoryEvents.CONNECT_EVENT, true);
            mEventHandler.sendMessage(msg);
        } else {
            Message msg = mEventHandler.obtainMessage(AccessoryEvents.CONNECT_EVENT, false);
            mEventHandler.sendMessage(msg);
        }
    }
}
