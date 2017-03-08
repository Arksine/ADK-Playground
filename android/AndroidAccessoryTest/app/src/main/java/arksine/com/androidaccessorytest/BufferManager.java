package arksine.com.androidaccessorytest;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by eric on 3/4/17.
 */

public class BufferManager {

    private ConcurrentLinkedQueue<PacketBuffer> mBuffers;

    public BufferManager() {
        this(10, 16384);
    }

    public BufferManager(int numBufs, int bufSize) {
        mBuffers = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < numBufs; i++) {
            PacketBuffer buf = new PacketBuffer(bufSize, this);
            mBuffers.add(buf);
        }
    }

    PacketBuffer getBuffer() {
        PacketBuffer buf = mBuffers.poll();
        if (buf == null) {
            return new PacketBuffer(16384, this);
        } else {
            return buf;
        }
    }

    void returnToQueue(PacketBuffer buf) {
        mBuffers.add(buf);
    }


}
