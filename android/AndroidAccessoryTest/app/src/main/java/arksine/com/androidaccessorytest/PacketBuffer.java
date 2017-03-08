package arksine.com.androidaccessorytest;

import java.nio.ByteBuffer;

/**
 * Created by eric on 3/4/17.
 */

public class PacketBuffer {
    private static final int PACKET_HEADER_SIZE = 6;


    private byte[] mBuffer;
    private BufferManager mParent;
    private int mPosition = 0;
    private int mCapacity;
    private int mPayloadSize = 0;


    public PacketBuffer(int capacity, BufferManager parent) {
        mCapacity = capacity;
        mParent = parent;
        mBuffer = new byte[mCapacity];
    }

    public PacketBuffer(BufferManager parent) {
        this(16384, parent);
    }

    public int payloadStartIndex() {
        return PACKET_HEADER_SIZE;
    }

    public void incrementPosition(int bytesReceived) {
        this.mPosition += bytesReceived;
    }

    public int position() {
        return mPosition;
    }

    public int getPayloadSize() {
        return mPayloadSize;
    }

    public void setPayloadsize(int newSize) {
        mPayloadSize = newSize;

        if ((mPayloadSize + PACKET_HEADER_SIZE) > mCapacity) {
            this.resize(newSize);
        }
    }

    public int remaining() {
        return mCapacity - mPosition;
    }


    public int headerRemaining() {
        int remaining = PACKET_HEADER_SIZE - this.mPosition;
        return ((remaining < 0) ? 0 : remaining);
    }

    public int payloadRemaining() {
        // Payload index starts after header, so we must add it to the position to get an accurate
        // count
        int remaining = (this.mPayloadSize + PACKET_HEADER_SIZE) - this.mPosition;
        return ((remaining < 0) ? 0 : remaining);
    }


    public int capacity() {
        return mCapacity;
    }

    public byte[] checkOverrun() {
        int overrunCount = this.mPosition - (this.mPayloadSize + PACKET_HEADER_SIZE);
        if (overrunCount > 0) {
            byte[] overrrun = new byte[overrunCount];
            int overRunPos = this.mPosition - overrunCount;
            System.arraycopy(this.mBuffer, overRunPos, overrrun, 0, overrunCount);
            // set the position back the the end of the payload
            this.mPosition = overRunPos;
            return overrrun;
        } else {
            // No overrun
            return null;
        }
    }

    public void putBytes(byte[] data) {
        if (data.length > 0) {
            System.arraycopy(data, 0, this.mBuffer, this.mPosition, data.length);
            this.mPosition += data.length;
        }
    }

    public void readIntoBuffer(byte[] data, int count) {
        System.arraycopy(data, 0, this.mBuffer, this.mPosition, count);
        this.mPosition += count;
    }


    public void clear() {
        // Clear limit and return to queue
        this.mPosition = 0;
        this.mPayloadSize = 0;
        if (this.mParent != null)
            this.mParent.returnToQueue(this);
    }

    /**
     * Resize the buffer to fit the requested payload size
     * @param payloadSize
     */

    private void resize(int payloadSize) {

        byte[] newBuf = new byte[payloadSize + PACKET_HEADER_SIZE];
        if (this.mPosition > 0) {
            System.arraycopy(mBuffer, 0, newBuf, 0, this.mPosition);
        }
        mBuffer = newBuf;
        mCapacity = payloadSize + PACKET_HEADER_SIZE;

    }

    public byte[] getArray() {
        return mBuffer;
    }

    public ByteBuffer getHeaderBuffer() {
        ByteBuffer headerBuf = ByteBuffer.wrap(this.mBuffer);
        headerBuf.position(0);
        headerBuf.limit(PACKET_HEADER_SIZE);
        return headerBuf;
    }

    public ByteBuffer getPayloadBuffer() {
        ByteBuffer payloadBuf = ByteBuffer.wrap(this.mBuffer);
        payloadBuf.position(PACKET_HEADER_SIZE);  // position starts after the packet index
        payloadBuf.limit(PACKET_HEADER_SIZE + mPayloadSize);
        return payloadBuf;
    }

 }
