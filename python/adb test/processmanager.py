"""

"""
import sys
from uvcprocess import UVCProcess
from android_accessory import AccessoryProcess
if sys.platform == 'win32':
    from multiprocessing import Process
    from greenpipe import Pipe
else:
    from multiprocessing import Process, Pipe


class ProcessManager(object):
    def __init__(self):
        self._host_uvc_pipe, self._sio_uvc_pipe = Pipe()
        self._host_acc_pipe, self._sio_acc_pipe = Pipe()
        # if using greenpipe, I need to set a timeout
        #self._host_pipe.settimeout(2)

    def _sio_process(self, uvc_pipe):
        import connection
        connection.start_server((uvc_pipe,))

    def _uvc_process(self):
        pass

    def run(self):
        sio_proc = Process(target=self._sio_process,
                           args=(self._sio_uvc_pipe,))
        sio_proc.start()
        accessory_proc = AccessoryProcess(self._host_acc_pipe)
        accessory_proc.start()
        uvc_proc = UVCProcess(self._host_uvc_pipe)
        uvc_proc.start()
        sio_proc.join()

        if accessory_proc.is_alive():
            self._sio_acc_pipe.send('EXIT')
            accessory_proc.join()
        if uvc_proc.is_alive():
            self._sio_uvc_pipe.send('CLOSE')
            uvc_proc.join()
        # TODO: start and join other processes



if __name__ == '__main__':
    procman = ProcessManager()
    procman.run()
    