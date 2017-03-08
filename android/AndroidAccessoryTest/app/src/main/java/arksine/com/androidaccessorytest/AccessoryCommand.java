package arksine.com.androidaccessorytest;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Enumeration to represent command sent to and received from the android accessory.
 * Commands are represented two bytes (can be converted to short).  Because Ordinal is not
 * necessarily consistent it is not used as the stored value.  It can however be used as
 * a Parcel ID.
 *
 * This Enum implements Parcelable so that it may be passed via Intent, Message, or AIDL.
 */

public enum AccessoryCommand implements Parcelable {
    NONE(new byte[]{(byte)0x00, (byte)0x00}),
    TEST(new byte[]{(byte)0x00, (byte)0x01}),
    CAM_FRAME(new byte[]{(byte)0x00, (byte)0x02}),
    EXIT(new byte[]{(byte)0xFF, (byte)0xFF});

    private static final String TAG = AccessoryCommand.class.getSimpleName();
    private static final AccessoryCommand[] COMMAND_ARRAY = AccessoryCommand.values();
    final byte[] mBytes;

    public static final Creator<AccessoryCommand> CREATOR = new Creator<AccessoryCommand>() {
        @Override
        public AccessoryCommand createFromParcel(Parcel source) {
            return COMMAND_ARRAY[source.readInt()];
        }

        @Override
        public AccessoryCommand[] newArray(int size) {
            return new AccessoryCommand[size];
        }
    };

    AccessoryCommand(byte[] bytes) {
        this.mBytes = bytes;
    }

    public byte[] getBytes() {
        return this.mBytes;
    }

    public short getValue() {
        return (ByteBuffer.wrap(this.mBytes).getShort());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.ordinal());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Compares an incoming byte value to the integer representation of all the values
     * in the RadioCommand enum.  If found, the RadioCommand is returned.
     *
     * @param byteValue     The integer representation of the value to find
     * @return              The matching command if found, null if not found
     */
    public static AccessoryCommand getCommandFromValue(int byteValue) {

        for (AccessoryCommand cmd : COMMAND_ARRAY) {
            if (cmd.getValue() == byteValue) {
                return cmd;
            }
        }

        Log.i(TAG, "No matching Command found for value: " + byteValue);
        return AccessoryCommand.NONE;
    }
}
