import socket
import pickle

class _GreenConnection(object):
    def __init__(self, sock):
        sock.setblocking(True)
        self.sock = sock

    def fileno(self):
        return self.sock.fileno()

    def send(self, obj):
        pickle.dump(obj, self, protocol=-1)

    def write(self, s):
        self.sock.sendall(s)

    def recv(self):
        return pickle.load(self)

    def read(self, n):
        return self.sock.recv(n)

    def settimeout(self, timeout):
        self.sock.settimeout(timeout)

    def readline(self):
        buf = b''
        c = None
        while c != '\n':
            c = self.sock.recv(1)
            buf += c
        return buf

def Pipe():
    s1, s2 = socket.socketpair()
    return (_GreenConnection(s1), _GreenConnection(s2))


