package com.arksine.adbtest;


import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

/**
 * Created by Eric on 3/11/2017.
 */

public class SioManager {
    // TODO: need callback handler passed to constructor

    private static final String TAG = SioManager.class.getSimpleName();
    private static final String EVENT_UPGRADE = "upgrade";
    private static final String EVENT_UPGRADE_ERROR = "upgradeError";
    private static final String DEFAULT_URI = "http://127.0.0.1";
    private static final String[] TRANSPORTS = {"websocket"};

    private Socket mIoSocket;
    private EventHandler mEventHandler;
    private AtomicBoolean mConnected = new AtomicBoolean(false);

    public SioManager (EventHandler eventHandler){
        // TODO: the DEFAULT_URI needs to be an option.  Default is localhost (connection over ADB)
        this.mEventHandler = eventHandler;

        IO.Options options = new IO.Options();
        options.transports = TRANSPORTS;
        options.upgrade = true;
        options.port = 8000;

        try {
            mIoSocket = IO.socket(DEFAULT_URI, options);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        // TODO: add more listeners
        mIoSocket.on(Socket.EVENT_CONNECT, onConnect)
                .on(Socket.EVENT_DISCONNECT, onDisconnect)
                .on(Socket.EVENT_CONNECT_ERROR, onError)
                .on(EVENT_UPGRADE, onTransportUpgrade)
                .on(EVENT_UPGRADE_ERROR, onUpgradeError)
                .on("TEST", onTest)
                .on("CAM_FRAME", onFrameReceived);

    }

    public void connect() {
        if (!mConnected.get()) {
            mIoSocket.connect();
        }
    }

    public void disconnect() {
        if (mConnected.compareAndSet(true, false)) {
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


    private final Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... objects) {
            Log.i(TAG, "Socket IO connected");
            mConnected.set(true);
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
            Log.i(TAG, "Socket IO connection error");
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
