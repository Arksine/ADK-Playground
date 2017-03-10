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

    private static final String MANUFACTURER = "Arksine";
    private static final String MODEL = "AccesoryTest";
    private static final String ACTION_USB_PERMISSION = "com.arksine.accessorytest.USB_PERMISSION";

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
    private BufferManager mBufferManager;

    AccessoryManager(Context context, Handler eventHandler) {
        this.mContext = context;
        this.mEventHandler = eventHandler;
        this.mUsbManger = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        this.mBufferManager = new BufferManager(10, 32768);

        HandlerThread handlerThread = new HandlerThread("Write Handler",
                Process.THREAD_PRIORITY_DEFAULT);
        handlerThread.start();
        Handler.Callback handlerCb = new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                byte[] data = (byte[])msg.obj;
                if (mOutputStream != null) {
                    AccessoryCommand cmd = AccessoryCommand.fromOrdinal(msg.arg1);
                    Log.d(TAG, "Write Command: " + cmd);

                    if (cmd != AccessoryCommand.NONE) {
                        ByteBuffer headerBuf = ByteBuffer.allocate(PACKET_HEADER_SIZE);
                        int payloadSize = (data != null) ?  data.length : 0;
                        headerBuf.put(cmd.getBytes());
                        headerBuf.putInt(payloadSize);

                        try {
                            mOutputStream.write(headerBuf.array());
                            if (data != null) {
                                mOutputStream.write(data);
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
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
                Log.d(TAG, "Unable to detect accessory.");
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
                            write(AccessoryCommand.TERMINATE, null);
                        } else {
                            write(AccessoryCommand.EXIT, null);
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

    void write(AccessoryCommand command, final byte[] data) {
        Message msg = mWriteHandler.obtainMessage();
        msg.obj = data;
        msg.arg1 = command.ordinal();
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
        int packetIndex = 60;
        boolean isHeader = true;
        AccessoryCommand command;
        byte[] inputBuffer = new byte[16384];
        PacketBuffer packetBuffer = mBufferManager.getBuffer();

        outerloop:
        while (mIsReading.get()) {
            try {
                bytesRead = mInputStream.read(inputBuffer);
            } catch (IOException e) {
                mIsReading.set(false);
                break;
            }

            if (bytesRead > 0) {

                packetBuffer.readIntoBuffer(inputBuffer, bytesRead);

                // TODO: I should have a confirmation packet as well.  It can't be a checksum
                // because packets are too large.
                boolean packetChecked = false;
                while (!packetChecked) {
                    packetChecked = true;

                    if (isHeader && packetBuffer.headerRemaining() <= 0) {
                        ByteBuffer headerBuf = packetBuffer.getHeaderBuffer();
                        command = AccessoryCommand.fromValue(headerBuf.getShort());
                        if (command == AccessoryCommand.EXIT) {
                            Log.i(TAG, "Exit recieved");
                            mIsReading.set(false);
                            break outerloop;
                        }

                        int size = headerBuf.getInt();
                        packetBuffer.setPayloadsize(size);
                        isHeader = false;
                        packetIndex--;
                        if (packetIndex == 0) {
                            Log.i(TAG, "Header Command: " + command.toString());
                            Log.i(TAG, "Payload size: " + String.valueOf(size));
                            packetIndex = 60;
                        }

                    }

                    if (!isHeader && packetBuffer.payloadRemaining() <= 0) {

                        // check for overrun
                        byte[] overrun = packetBuffer.checkOverrun();

                        // Send data for processing
                        Message msg = mEventHandler.obtainMessage(AccessoryEvents.DATA_EVENT,
                                packetBuffer);
                        mEventHandler.sendMessage(msg);

                        isHeader = true;

                        // get the next buffer in the queue
                        packetBuffer = mBufferManager.getBuffer();
                        if (overrun != null) {
                            packetBuffer.putBytes(overrun);
                            // We need to inspect the remains of this packet before
                            // reading again
                            packetChecked = false;
                        }

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
            this.write(AccessoryCommand.APP_CONNECTED, null);   // Tell the accessory we are connected
            Message msg = mEventHandler.obtainMessage(AccessoryEvents.CONNECT_EVENT, true);
            mEventHandler.sendMessage(msg);
        } else {
            Log.d(TAG, "Unable to open Accessory File Descriptor");
            Message msg = mEventHandler.obtainMessage(AccessoryEvents.CONNECT_EVENT, false);
            mEventHandler.sendMessage(msg);
        }
    }

}
