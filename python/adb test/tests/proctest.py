import sys
if sys.platform == 'win32':
    from multiprocessing import Process
    from greenpipe import Pipe
else:
    from multiprocessing import Process, Pipe
import socket
import time


def testproc(outpipe):
    import eventlettest
    eventlettest.run(outpipe)

if __name__ == '__main__':
    parent_pipe, child_pipe = Pipe()
    #parent_pipe.settimeout(5)
    proc = Process(target=testproc, args=(child_pipe,))
    proc.start()
    i = 0
    while 1:
        time.sleep(3)
        parent_pipe.send(i)
        i += 10
        

