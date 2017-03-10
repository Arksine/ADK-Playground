from multiprocessing import Process
import queue
from struct import pack
import signal
import threading
import uvclite

class UVCProcess(Process):
    def __init__(self, write_queue, pipe):
        super(UVCProcess, self).__init__()
        self._write_queue = write_queue
        self._comm_pipe = pipe
        #self._read_thread = None
        self._process_running = True
        self._is_reading = True
        self._cap_dev = None

    def _read(self):
        print("UVC Capture Thread Started")
        while self._is_reading:
            try:
                frame = self._cap_dev.get_frame()
            except uvclite.UVCError as err:
                print(err.args)
                if err.errno == 110 or err.errno == 500:
                    # 110 = read timeout, 500 = Null Frame
                    continue
                else:
                    raise err
            header = pack('>I', len(frame.data))
            try:
                self._write_queue.put((header, frame.data), block=False)
            except queue.Full:
                pass

    def _read_callback(self, frame, user):
        if self._is_reading:
            header = pack('>I', len(frame.data))
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
            # self._cap_dev.set_stream_format()
            self._cap_dev.start_streaming()
            while self._process_running:
                command = self._comm_pipe.recv()
                if command[0] == 100:
                    if not self._is_reading:
                        self._is_reading = True
                        #self._read_thread = threading.Thread(target=self._read)
                        #self._read_thread.start()
                        print("Start Streaming")
                elif command[0] == 200:
                    if self._is_reading:
                        self._is_reading = False
                        print("Stop Streaming")
                elif command[0] == 300:
                    print("Close Capture device")
                    self._is_reading = False
                    break
            print("Closing")
            self._comm_pipe.close()
            self._cap_dev.stop_streaming()
            self._cap_dev.close()
