from multiprocessing import Process, Pipe
import time

def testproc(pipe):
    while 1:
        resp = pipe.recv()
        print( resp)

if __name__ == '__main__':
    parent_pipe, child_pipe = Pipe()
    #parent_pipe.settimeout(5)
    proc = Process(target=testproc, args=(child_pipe,))
    proc.start()
    test =  bytearray(b'testerer')
    while 1:
        time.sleep(2)
        parent_pipe.send(test)