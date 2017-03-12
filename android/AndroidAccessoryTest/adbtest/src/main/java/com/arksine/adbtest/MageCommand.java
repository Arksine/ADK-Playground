package com.arksine.adbtest;

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

public enum MageCommand implements Parcelable {
    NONE,
    TEST,
    CAM_FRAME,
    CAM_START,
    CAM_STOP,
    EXIT;

    private static final String TAG = MageCommand.class.getSimpleName();
    private static final MageCommand[] COMMAND_ARRAY = MageCommand.values();

    public static final Creator<MageCommand> CREATOR = new Creator<MageCommand>() {
        @Override
        public MageCommand createFromParcel(Parcel source) {
            return COMMAND_ARRAY[source.readInt()];
        }

        @Override
        public MageCommand[] newArray(int size) {
            return new MageCommand[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.ordinal());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static MageCommand fromOrdinal(int ordinal) {
        if (ordinal >= 0 && ordinal < COMMAND_ARRAY.length) {
            return COMMAND_ARRAY[ordinal];
        } else {
            return MageCommand.NONE;
        }
    }

}
