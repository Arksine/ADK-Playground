package arksine.com.androidaccessorytest;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by eric on 3/4/17.
 */

public class BufferManager {

    private ConcurrentLinkedQueue<InputBuffer> mBuffers;

    public BufferManager() {
        this(10, 16384);
    }

    public BufferManager(int numBufs, int bufSize) {
        mBuffers = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < numBufs; i++) {
            InputBuffer buf = new InputBuffer(bufSize, this);
            mBuffers.add(buf);
        }
    }

    InputBuffer getBuffer(int bufSize) {
        // TODO: This is the easy way to do it, should I implement this as a circular buffer?
        // Would it significantly increase access time (If there are say, 100 requests/sec)
        InputBuffer buf = mBuffers.poll();
        if (buf == null) {
            return new InputBuffer((bufSize > 16384) ? bufSize : 16384, this);
        } else {
            if (buf.capacity() < bufSize)
                buf.resize(bufSize);
            return buf;
        }
    }

    void returnToQueue(InputBuffer buf) {
        mBuffers.add(buf);
    }


}
