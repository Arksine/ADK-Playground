package com.arksine.adbtest;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.net.ServerSocket;

/**
 * Created by Eric on 3/11/2017.
 */

public class EventHandler extends Handler {
    private static final String TAG = EventHandler.class.getSimpleName();

    interface ServiceCallbacks {
        void updateNotification(boolean connected);
        void stopService();
    }

    private EventManager<MageEvents> mMageEvents;
    private ServiceCallbacks mServiceCallbacks;

    public EventHandler(Looper looper, EventManager<MageEvents> mageEvents, ServiceCallbacks cbs) {
        super(looper);
        this.mMageEvents = mageEvents;
        this.mServiceCallbacks = cbs;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MageEvents.CONNECT_EVENT: {
                boolean connected = (boolean)msg.obj;
                mServiceCallbacks.updateNotification(connected);

                if (mMageEvents.isCallbackAvailable()) {
                    try {
                        mMageEvents.getCallback().onConnected(connected);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

                break;
            }
            case MageEvents.DISCONNECT_EVENT: {
                mServiceCallbacks.updateNotification(false);

                // TODO: with more advanced functionality I might need to do more cleanup here
                if (mMageEvents.isCallbackAvailable()) {
                    try {
                        mMageEvents.getCallback().onDisconnected();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }

                break;
            }
            case MageEvents.TEST_EVENT: {
                if (mMageEvents.isCallbackAvailable()) {
                    try {
                        mMageEvents.getCallback().onTest((String)msg.obj);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                break;
            }
            case MageEvents.CAM_FRAME_EVENT: {
                if (mMageEvents.isCallbackAvailable()) {
                    try {
                        mMageEvents.getCallback().onFrameReceived((byte[])msg.obj);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                break;
            }
            case MageEvents.LOG_EVENT: {
                if (mMageEvents.isCallbackAvailable()) {
                    try {
                        mMageEvents.getCallback().onLogEvent((String)msg.obj);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                break;
            }
        }
    }
}
