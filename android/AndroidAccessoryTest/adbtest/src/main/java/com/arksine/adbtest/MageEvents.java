package com.arksine.adbtest;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Created by eric on 2/27/17.
 */

public abstract class MageEvents implements IInterface {
    public static final int CONNECT_EVENT = 0;
    public static final int DISCONNECT_EVENT = 1;
    public static final int TEST_EVENT = 2;
    public static final int CAM_FRAME_EVENT = 3;
    public static final int LOG_EVENT = 4;

    private Binder mBinder = new Binder();

    public abstract void onConnected(final boolean status) throws RemoteException;
    public abstract void onDisconnected() throws RemoteException;
    public abstract void onTest(final String data) throws  RemoteException;
    public abstract void onFrameReceived(final byte[] frame) throws RemoteException;
    public abstract void onLogEvent(final String logInfo) throws RemoteException;

    @Override
    public IBinder asBinder() {
        return mBinder;
    }
}
