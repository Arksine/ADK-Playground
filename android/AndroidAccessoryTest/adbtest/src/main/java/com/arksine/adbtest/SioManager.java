package com.arksine.adbtest;


import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.EngineIOException;

/**
 * Created by Eric on 3/11/2017.
 */

public class SioManager {
    private static final String TAG = SioManager.class.getSimpleName();
    private static boolean DEBUG = false;
    private static final String EVENT_UPGRADE = "upgrade";
    private static final String EVENT_UPGRADE_ERROR = "upgradeError";
    private static final String DEFAULT_URI = "http://127.0.0.1:8000";
    private static final String[] TRANSPORTS = {"websocket"};

    private Socket mIoSocket;
    private EventHandler mEventHandler;
    private AtomicBoolean mConnected = new AtomicBoolean(false);
    private AtomicBoolean mAttemptingConnect = new AtomicBoolean(false);

    public SioManager (EventHandler eventHandler){
        // TODO: the DEFAULT_URI needs to be an option.  Default is localhost (connection over ADB)
        this.mEventHandler = eventHandler;

        IO.Options options = new IO.Options();
        options.transports = TRANSPORTS;
        options.upgrade = true;
        options.secure = false;

        try {
            mIoSocket = IO.socket(DEFAULT_URI, options);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // TODO: add more listeners
        mIoSocket.on(Socket.EVENT_CONNECT, onConnect);
        mIoSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
        mIoSocket.on(Socket.EVENT_CONNECT_ERROR, onError);
        mIoSocket.on(EVENT_UPGRADE, onTransportUpgrade);
        mIoSocket.on(EVENT_UPGRADE_ERROR, onUpgradeError);
        mIoSocket.on("TEST", onTest);
        mIoSocket.on("CAM_FRAME", onFrameReceived);

    }

    /**
     * Connect to socketio.  Only connect if not connected and not attempting
     * connection
     */
    public void connect() {
        if (!mConnected.get() && mAttemptingConnect.compareAndSet(false, true)) {
            mIoSocket.connect();
        }
    }

    /**
     * Disconnect socketio.  We disconnects from connected server, OR stops attempting
     * connections if the client is in the process of repeatedly attempting to connect
     */
    public void disconnect() {
        if (mConnected.compareAndSet(true, false) ||
                mAttemptingConnect.compareAndSet(true, false)) {
            mConnected.set(false);
            mIoSocket.disconnect();
        }
    }

    public boolean isConnected() {
        return mConnected.get();
    }

    public void sendCommand(MageCommand cmd, Object data) {
        if (mConnected.get()) {
            mIoSocket.emit(cmd.toString(), data);
        }
    }

    // TODO; I need a listener for reconnect attempts, so I can set attempting reconnect
    // Appropriately


    private final Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Socket IO connected:");
            mConnected.set(true);
            mAttemptingConnect.set(false);
            Message msg = mEventHandler.obtainMessage(MageEvents.CONNECT_EVENT, true);
            mEventHandler.sendMessage(msg);
        }
    };

    private final Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Socket IO disconnected");
            mConnected.set(false);
            Message msg = mEventHandler.obtainMessage(MageEvents.DISCONNECT_EVENT);
            mEventHandler.sendMessage(msg);
        }
    };

    private final Emitter.Listener onTransportUpgrade = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Transport upgraded to websocket");
            Message msg = mEventHandler.obtainMessage(MageEvents.LOG_EVENT,
                    "Transport Upgraded to websocket");
            mEventHandler.sendMessage(msg);
        }
    };

    private final Emitter.Listener onUpgradeError = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Transport upgrade failed");
            Message msg = mEventHandler.obtainMessage(MageEvents.LOG_EVENT,
                    "Transport Upgrade Error");
            mEventHandler.sendMessage(msg);
        }
    };

    private final Emitter.Listener onError = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Socket IO connection error:");
            if (DEBUG) {
                for (Object obj : objects) {
                    if (obj instanceof EngineIOException) {
                        ((EngineIOException) obj).printStackTrace();
                    } else {
                        Log.i(TAG, obj.toString());
                    }
                }
            }
            Message msg = mEventHandler.obtainMessage(MageEvents.LOG_EVENT,
                    "Connection Error");
            mEventHandler.sendMessage(msg);
        }
    };

    private final Emitter.Listener onTest = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Socket IO test packet received");
            String testdata = (String)objects[0];
            Message msg = mEventHandler.obtainMessage(MageEvents.TEST_EVENT, testdata);
            mEventHandler.sendMessage(msg);
        }
    };

    private final Emitter.Listener onFrameReceived = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            byte[] frame = (byte[])objects[0];
            Message msg = mEventHandler.obtainMessage(MageEvents.CAM_FRAME_EVENT, frame);
            mEventHandler.sendMessage(msg);
        }
    };
}
