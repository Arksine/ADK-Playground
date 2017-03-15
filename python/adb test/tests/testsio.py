'''
Implements main socket io server
'''
import socketio
import eventlet
from eventlet import green
from flask import Flask, render_template

sio = socketio.Server(async_mode='eventlet', logger=True)
app = Flask(__name__)
comm_pipes = None
client_mgr = None

class ClientManager(object):
    def __init__(self, sid_in, sio_in, pipes):
        self._sid = sid_in
        self._sio = sio_in
        # TODO: add pipes for other processes
        self._uvc_pipe = pipes[0]
        self._is_running = False

    def send_uvc_command(self, command):
        eventlet.spawn_n(self._write_to_pipe, self._uvc_pipe, command)

    def start_listeners(self):
        self._is_running = True
        eventlet.spawn_n(self._uvc_listener)

    def _write_to_pipe(self, pipe, command):
        r, w, x = green.select.select([], [pipe.fileno()], [])
        if w:
            pipe.send(command)

    def _uvc_listener(self):
        while self._is_running:
            r, w, x = green.select.select([self._uvc_pipe.fileno()], [], [])
            if r:
                frame = self._uvc_pipe.recv()
                self._sio.emit('CAM_FRAME', data=frame, room=self._sid)

    def close(self):
        self._is_running = False


@app.route('/')
def index():
    return render_template('index.html')

@sio.on('connect')
def connect(sid, environ):
    print("Connected")
    global client_mgr
    if not client_mgr:
        client_mgr = ClientManager(sid, sio, comm_pipes)

@sio.on('disconnect')
def disconnect(sid):
    global client_mgr
    if client_mgr:
        client_mgr.close()
        del client_mgr
        client_mgr = None

@sio.on('TEST')
def handle_test(sid, data):
    print("Data received: %s" %data)
    # Echo back
    echo = 'Echo: ' + data
    sio.emit('TEST', echo, sid)

@sio.on('CAM_START')
def handle_start_camera(sid, data):
    if client_mgr:
        client_mgr.send_uvc_command('START')

@sio.on('CAM_STOP')
def handle_stop_camera(sid, data):
    if client_mgr:
        client_mgr.send_uvc_command('STOP')
    pass

def start_server(pipes):
    # TODO: check pipes to make sure they are not None, and make sure they
    # are tuple instance
    global comm_pipes
    global app
    global sio

    comm_pipes = pipes
    app = socketio.Middleware(sio, app)
    eventlet.wsgi.server(eventlet.listen(('', 8000)), app,
                         max_size=1)

if __name__ == '__main__':
    import greenpipe
    pone, ptwo = greenpipe.Pipe()
    comm_pipes = (ptwo,)
    app = socketio.Middleware(sio, app)
    eventlet.wsgi.server(eventlet.listen(('', 8000)), app)

