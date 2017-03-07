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

    private static final int PACKET_HEADER_SIZE = 6;
    private static final byte[] EXIT_BYTES = {(byte)0xFF, (byte)0xFF};
    private static final byte[] TERMINATE_BYTES = {(byte)0xFF, (byte)0xFE};
    private static final byte[] CONNECTED_BYTES = {(byte)0xFF, (byte)0xFD};

    private BufferManager mBufferManger = new BufferManager();

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

                        byte[] size_bytes = ByteBuffer.allocate(4).putInt(data.length).array();
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
        AccessoryCommand command;
        int size ;  // length of packet (max 65535 for now)
        InputBuffer header = new InputBuffer(PACKET_HEADER_SIZE, null);

        // Get a ByteBuffer view and set its limit to 4
        ByteBuffer headerBuf = header.getAsByteBuffer();
        headerBuf.limit(PACKET_HEADER_SIZE);

        outerloop:
        while (mIsReading.get()) {

            // Get Header
            if (!header.readIntoBuffer(mInputStream, PACKET_HEADER_SIZE)) {
                Log.d(TAG, "Header read failed");
                mIsReading.set(false);
                break outerloop;
            }
            // Rewind header buffer so I can read from it
            header.clear();
            headerBuf.rewind();


            command = AccessoryCommand.getCommandFromValue(headerBuf.getShort());

            // Check for an exit command
            if (command == AccessoryCommand.EXIT) {
                Log.i(TAG, "Exit recieved");
                mIsReading.set(false);
                break outerloop;
            }

            size = headerBuf.getInt();

            // Get an available buffer and read the payload into it
            InputBuffer dataBuf = mBufferManger.getBuffer(size);
            if (!dataBuf.readIntoBuffer(mInputStream, size)) {
                Log.d(TAG, "Data read failed");
                mIsReading.set(false);
                break outerloop;
            }

            // TODO: process command/data in a command handler, for now just send the data to
            // the test activity
            Message msg = mEventHandler.obtainMessage(AccessoryEvents.DATA_EVENT,
                    dataBuf);
            mEventHandler.sendMessage(msg);

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
