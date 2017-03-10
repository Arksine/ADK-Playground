package arksine.com.androidaccessorytest;

import android.hardware.usb.UsbAccessory;

/**
 * Created by eric on 2/27/17.
 */

public interface AccessoryControlInterface {
    void attemptConnect(UsbAccessory accessory);
    boolean isOpen();
    boolean sendCommand(AccessoryCommand cmd, byte[] data);
    boolean writeBytes(byte[] data);
    boolean writeShort(short data);
    boolean writeInt(int data);
    boolean writeString(String data);
    void closeAccessory(boolean closeAll);
}
