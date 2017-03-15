package com.arksine.adbtest;

import android.app.Application;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Created by Eric on 3/15/2017.
 */

public class MainApplication extends Application {
    private static final String DEFAULT_URI = "http://192.168.1.102:8000";
    private static final String[] TRANSPORTS = {"websocket"};

    private Socket mSocket;
    {
        IO.Options options = new IO.Options();
        options.transports = TRANSPORTS;
        options.upgrade = true;
        options.secure = false;

        try {
            mSocket = IO.socket(DEFAULT_URI, options);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public Socket getSioSocket() {
        return mSocket;
    }
}
