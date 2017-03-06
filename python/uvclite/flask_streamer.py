#!/usr/bin/env python
from flask import Flask, render_template, Response

import uvclite
import time

app = Flask(__name__)

@app.route('/')
def index():
    """Video streaming home page."""
    return render_template('index.html')


def gen(camera):
    """Video streaming generator function."""
    while True:
        try:
            frame = camera.get_frame()
        except uvclite.UVCError as err:
            print(err.args)
            if err.errno == 110 or err.errno == 500:
                # 110 = read timeout, 500 = Null Frame
                continue
            else:
                raise err
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame.data + b'\r\n')


@app.route('/video_feed')
def video_feed():
    """Video streaming route. Put this in the src attribute of an img tag."""
    return Response(gen(cap_dev),
                    mimetype='multipart/x-mixed-replace; boundary=frame')

if __name__ == '__main__':

    with uvclite.UVCContext() as context:
        cap_dev = context.find_device()
        cap_dev.open()
        cap_dev.start_streaming()
        app.run(host='0.0.0.0', debug=False, threaded=True)
        print("Exiting...")
        cap_dev.stop_streaming()
        print("Closing..")
        cap_dev.close()
        print("Clear Context")
