package com.arksine.adbtest;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Created by Eric on 3/11/2017.
 */

public class EventManager<E extends IInterface> implements IBinder.DeathRecipient {
    private E mCallback = null;

    public EventManager() {}

    public void registerCallback(E cb) {
        IBinder token = cb.asBinder();
        try {
            token.linkToDeath(this, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mCallback = cb;
    }

    public void unregisterCallback() {
        mCallback = null;
    }

    public boolean isCallbackAvailable() {
        return (this.mCallback != null);
    }

    public E getCallback() {
        return this.mCallback;
    }

    @Override
    public void binderDied() {
        mCallback = null;
    }
}
