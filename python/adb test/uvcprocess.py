from multiprocessing import Process
import signal
import threading
import uvclite
import socketio

class UVCProcess(Process):
    def __init__(self, pipe):
        super(UVCProcess, self).__init__()
        self._comm_pipe = pipe
        self._process_running = True
        self._cap_dev = None

    def _read_callback(self, frame, user):
        self._comm_pipe.send(frame.data)

    def _signal_handler(self, sig, stack):
        self._process_running = False
        raise KeyboardInterrupt()

    def run(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            signal.signal(signum, self._signal_handler)

        with uvclite.UVCContext() as context:
            self._cap_dev = context.find_device()
            self._cap_dev.open()
            self._cap_dev.set_callback(self._read_callback, 1234)

            print("UVC Ready to Capture")
            while self._process_running:
                command = self._comm_pipe.recv()
                if command == 'START':
                    self._cap_dev.start_streaming()
                elif command == 'STOP':
                    self._cap_dev.stop_streaming()
                elif command == 'CLOSE':
                    print("Close Capture device")
                    self._process_running = False
                    break
            self._cap_dev.close()
            self._comm_pipe.close()
