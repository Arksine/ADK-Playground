package arksine.com.androidaccessorytest;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Created by eric on 3/4/17.
 */

public class InputBuffer {
    private byte[] mBuffer;
    private BufferManager mParent;

    private int mCapacity;
    private int mLimit = 0;


    public InputBuffer(int capacity, BufferManager parent) {
        mCapacity = capacity;
        mParent = parent;

        mBuffer = new byte[mCapacity];

    }

    public InputBuffer(BufferManager parent) {
        this(16384, parent);
    }

    public int limit() {
        return mLimit;
    }

    public int capacity() {
        return mCapacity;
    }

    /**
     * Reads from the input stream provided into the input buffer,
     * until either the count is reached or an error occurs
     *
     * @param inStream          The stream to read from
     * @param bytesToRead       The number of bytes to read
     * @return True if successful, false if an error is reached
     */
    public boolean readIntoBuffer(FileInputStream inStream, int bytesToRead) {
        int bytesRead;
        while (bytesToRead > 0) {
            try {
                bytesRead = inStream.read(mBuffer, mLimit, bytesToRead);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
            mLimit += bytesRead;
            bytesToRead = bytesToRead - bytesRead;
        }

        return true;
    }

    public void clear() {
        // Clear limit and return to queue
        this.mLimit = 0;
        if (this.mParent != null)
            this.mParent.returnToQueue(this);
    }

    public void resize(int newSize) {
        if (mLimit == 0) {
            mBuffer = new byte[newSize];
            mCapacity = newSize;
        } else if (newSize > mCapacity){
            // Should I really allow this?
            byte[] newBuf = new byte[newSize];
            System.arraycopy(mBuffer, 0, newBuf, 0, mLimit);
            mBuffer = newBuf;
            mCapacity = newSize;
        }
    }

    public byte[] getBuffer() {
        return mBuffer;
    }

    public ByteBuffer getAsByteBuffer() {
        ByteBuffer buf = ByteBuffer.wrap(mBuffer);
        buf.limit(mLimit);
        return buf;
    }
 }
