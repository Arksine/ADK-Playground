package arksine.com.androidaccessorytest;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Created by eric on 2/27/17.
 */

public abstract class AccessoryEvents implements IInterface {
    public static final int CONNECT_EVENT = 0;
    public static final int DISCONNECT_EVENT = 1;
    public static final int DATA_EVENT = 2;

    private Binder mBinder = new Binder();

    public abstract void onConnected(final boolean status) throws RemoteException;
    public abstract void onDisconnected() throws RemoteException;
    public abstract void onDataReceived(final InputBuffer data) throws  RemoteException;

    @Override
    public IBinder asBinder() {
        return mBinder;
    }
}
