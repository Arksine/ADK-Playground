from multiprocessing import Process
import queue
import signal
from struct import pack
import threading
from constants import CAM_FRAME
import uvclite

class UVCProcess(Process):
    def __init__(self, write_queue, pipe):
        super(UVCProcess, self).__init__()
        self._write_queue = write_queue
        self._comm_pipe = pipe
        self._process_running = True
        self._cap_dev = None

    def _read_callback(self, frame, user):
        size_bytes = pack('>I', len(frame.data))
        header = CAM_FRAME + size_bytes
        try:
            self._write_queue.put((header, frame.data), block=False)
        except queue.Full:
            pass

    def _signal_handler(self, sig, stack):
        self._process_running = False

    def run(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            signal.signal(signum, self._signal_handler)

        with uvclite.UVCContext() as context:
            self._cap_dev = context.find_device()
            self._cap_dev.open()
            self._cap_dev.set_callback(self._read_callback, 1234)
            self._cap_dev.start_streaming()
            print("UVC Ready to Capture")
            while self._process_running:
                command = self._comm_pipe.recv()
                if command[0] == 200:
                    print("Close Capture device")
                    self._process_running = False
                    break
            self._comm_pipe.close()
            self._cap_dev.stop_streaming()
            self._cap_dev.close()
