package com.arksine.adbtest;

import android.hardware.usb.UsbAccessory;

/**
 * Created by eric on 2/27/17.
 */

public interface MageControlInterface {
    boolean isConnected();
    void sendTestCommand(MageCommand cmd, String data);
    void disconnect();
    void refreshConnection();
}
