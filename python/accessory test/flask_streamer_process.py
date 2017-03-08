#!/usr/bin/python

# Copyright 2017 Eric Callahan
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import print_function
from flask import Flask, render_template, Response

import uvcprocesstest
import time
from multiprocessing import Process, Pipe, Queue
import queue

app = Flask(__name__)
frame_queue = Queue(10)
user_check = True

@app.route('/')
def index():
    """Video streaming home page."""
    return render_template('index.html')


def gen():
    """Video streaming generator function."""
    while True:
        try:
            data = frame_queue.get(timeout=.5)
        except queue.Empty:
            break
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + data[1] + b'\r\n')


@app.route('/video_feed')
def video_feed():
    """Video streaming route. Put this in the src attribute of an img tag."""
    return Response(gen(),
                    mimetype='multipart/x-mixed-replace; boundary=frame')


if __name__ == '__main__':
    parent_pipe, child_pipe = Pipe()

    uvc_process = uvcprocesstest.UVCProcess(frame_queue, child_pipe)
    uvc_process.start()
    app.run(host='0.0.0.0', debug=False, threaded=True)
    print("Send Close to Capture Dev")
    parent_pipe.send([300])
