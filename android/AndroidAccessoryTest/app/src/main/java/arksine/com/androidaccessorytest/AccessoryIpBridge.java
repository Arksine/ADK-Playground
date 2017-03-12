package arksine.com.androidaccessorytest;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by Eric on 3/10/2017.
 */
// TODO: This probably wont work.  Best to just do this over adb
public class AccessoryIpBridge extends Thread {
    private static String TAG = AccessoryIpBridge.class.getSimpleName();
    private static AccessoryIpBridge primary_instance = null;
    interface Callback {
        void onConnected(AccessoryIpBridge server);
    }

    private AtomicReference<ServerSocket> mServerSocket = new AtomicReference<>(null);
    private AtomicReference<Socket> mHostSocket = new AtomicReference<>(null);
    private AtomicReference<Socket> mClientSocket = new AtomicReference<>(null);

    private InputStream mClientInputStream;
    private OutputStream mClientOutputStream;

    private AtomicBoolean mIsRunning = new AtomicBoolean(false);

    private AccessoryIpBridge() {}

    public static AccessoryIpBridge getBridgeInstance() {
        if (primary_instance == null) {
            primary_instance = new AccessoryIpBridge();
            primary_instance.startServer();
        }
        return primary_instance;
    }

    private void startServer() {
        Thread connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket.set(new ServerSocket(8000, 0, InetAddress.getLoopbackAddress()));
                    mHostSocket.set(mServerSocket.get().accept());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        connectThread.start();
    }

    public Socket getHostSocket() {
        if (mServerSocket.get() != null) {
            if (mClientSocket.get() == null) {
                try {
                    this.mClientSocket.set(new Socket(InetAddress.getLoopbackAddress(), 8000));
                    this.mClientInputStream = this.mClientSocket.get().getInputStream();
                    this.mClientOutputStream = this.mClientSocket.get().getOutputStream();
                    this.start();
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (mHostSocket.get() == null) {
                Log.i(TAG, "Host socket not created");
            }
            return mHostSocket.get();
        } else {
            Log.i(TAG, "Host ServerSocket not created");
            return null;
        }
    }

    public void writeCommand(AccessoryCommand cmd, byte[] data) {

    }

    public void close() {
        // TODO: close thread, sockets, and streams

        primary_instance = null;
    }


    @Override
    public void run() {
        this.mIsRunning.set(true);

        while (this.mIsRunning.get()) {

            // TODO:
        }
    }
}
