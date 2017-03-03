#!/usr/bin/env python
from flask import Flask, render_template, Response

# emulated camera
# from camera import Camera

# Raspberry Pi camera module (requires picamera package)
# from camera_pi import Camera

import uvclite
import signal
import time

app = Flask(__name__)

@app.route('/')
def index():
    """Video streaming home page."""
    return render_template('index.html')


def gen(camera):
    """Video streaming generator function."""
    while True:
        frame = camera.get_frame()
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame.data + b'\r\n')


@app.route('/video_feed')
def video_feed():
    """Video streaming route. Put this in the src attribute of an img tag."""
    return Response(gen(cap_dev),
                    mimetype='multipart/x-mixed-replace; boundary=frame')
"""
def shutdown_server():
    if cap_dev:
        print("Shutting down device")
        cap_dev.stop_streaming()
        cap_dev.close()
    else:
        print("Device not open")

    func = request.environ.get('werkzeug.server.shutdown')
    if func is None:
        raise RuntimeError('Not running with the Werkzeug Server')
    func()
"""

"""
def frame_callback(in_frame, user):
    global frame
    global new_frame
    frame = in_frame
    new_frame = True
"""

if __name__ == '__main__':

    with uvclite.UVCContext() as context:
        #global cap_dev
        cap_dev = context.find_device()
        #cap_dev.set_callback(frame_callback)
        cap_dev.open()
        cap_dev.start_streaming()
        app.run(host='0.0.0.0', debug=False, threaded=True)
        print("Exiting...")
        cap_dev.stop_streaming()
        print("Closing..")
        cap_dev.close()
        print("Clear Context")
