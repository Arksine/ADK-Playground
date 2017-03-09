#!/usr/bin/python3
# coding=utf-8
"""pyusb_accessory_test.py

"""
# Copyright 2015 Christopher Blay <chris.b.blay@gmail.com>
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
#
# Base functionality credit to Christopher Blay's android_open_acccessory_bridge
# repository.  See android_open_accessory_bridge.py at
#
#      https://github.com/chris-blay/android-open-accessory-bridge
#
# Modifications Copyright 2016 Eric Callahan <kode4food@yahoo.com>
# All modifications are subject to the Apache license outlined above.

# pylint: disable=no-member
# pylint: disable=W0511,W0622,W0613,W0603,R0902

from __future__ import print_function, unicode_literals

from struct import pack, unpack
import sys
import time
import signal
from multiprocessing import Process, Pipe, Queue
import threading
import queue
import usb
from uvcprocess import UVCProcess
from constants import *

SHUTDOWN = False

def eprint(*args, **kwargs):
    """
    Simple helper to print to stderr.  Should work python 2 an 3.
    """
    print(*args, file=sys.stderr, **kwargs)

class USBReader(object):
    __HEADER_SIZE = 6
    # TODO: add other process pipes that need comms here (or just create the pipes here)
    def __init__(self, write_q):
        self._write_queue = write_q
        self.packet_size = self.__HEADER_SIZE  # initial amount to read
        self._is_header = True # first packet is header
        self._command = None

        self._uvc_pipe = None
        self._uvc_child_pipe = None
        self._uvc_process = None

    def __call__(self, accessory, data):
        if self._is_header:
            #data is the header portion of the packet
            hdr = unpack('>2sI', data)
            self._command = hdr[0]
            self.packet_size = hdr[1]
            if self.packet_size == 0:
                self._parse_payload(accessory, None)
            else:
                self._is_header = False
        else:
            self._parse_payload(accessory, data)
            self.packet_size = self.__HEADER_SIZE
            self._is_header = True

    def _parse_payload(self, accessory, data):

        if self._command == CMD_TERMINATE:
            eprint('Terminate Command Received')
            global SHUTDOWN
            SHUTDOWN = True
        elif self._command == CMD_EXIT:
            eprint('Exit app Command Received')
            accessory.signal_app_exit()
        elif self._command == CMD_APP_CONNECTED:
            eprint('Android Application Connected')
            accessory.app_connected = True
        elif self._command == CMD_CAM_START:
            self._start_uvc_process()
        elif self._command == CMD_CAM_STOP:
            # stop the uvc process in another thread so this one isn't blocked
            stop_uvc_thread = threading.Thread(target=self._stop_uvc_process)
            stop_uvc_thread.start()
        elif self._command == CMD_TEST:
            # Test simply takes a short, parses it, and echoes it
            # back to the device
            assert len(data) == 2
            value = unpack('>H', data)[0]
            eprint('Read in value: %d', value)
            value += 10
            out = pack('>H', value)
            accessory.write_command(CMD_TEST, out)

    def _start_uvc_process(self):
        if not self._uvc_process:
            eprint("Starting UVC Process")
            self._uvc_pipe, self._uvc_child_pipe = Pipe()
            self._uvc_process = UVCProcess(self._write_queue, self._uvc_child_pipe)
            self._uvc_process.start()

    def _stop_uvc_process(self):
        if self._uvc_process:
            eprint("Stopping UVC Process")
            self._uvc_pipe.send([200])
            self._uvc_process.join(1)
            if self._uvc_process.is_alive():
                self._uvc_process.terminate()
            self._uvc_process = None

    def close(self):
        self._stop_uvc_process()

class FindDevice(object):
    """ TODO: docstring"""
    def __init__(self, vendor_ids=COMPATIBLE_VIDS):
        self.vendor_ids = vendor_ids

    def __call__(self, device):
        if device.idVendor in self.vendor_ids:
            return True
        else:
            return False

class AndroidAccessory(object):
    """TODO: docstring here """
    def __init__(self, callback_obj, vendor_id=None, product_id=None,
                 write_queue=None):
        self.app_connected = False
        self._read_callback = callback_obj

        self._vendor_id = vendor_id
        self._product_id = product_id
        self._device = self._configure_and_open_device()
        self._endpoint_out, self._endpoint_in = self._get_endpoints()
        if write_queue:
            self._write_queue = write_queue
        else:
            self._write_queue = Queue()

        self._is_writing = True
        self._writer_thread = threading.Thread(target=self._write_thread)
        self._writer_thread.start()

    def __enter__(self):
        return self  # All 'enter' work is done in __init__().

    def __exit__(self, type, value, traceback):
        self.close()

    def _detect_device(self, attempts_left=5):
        # TODO: need to implement ability to find exact match as well
        device = usb.core.find(custom_match=FindDevice())

        if device:
            if (device.idVendor == ACCESSORY_VID and
                    device.idProduct in ACCESSORY_PID):
                return device, True
            else:
                return device, False
        elif attempts_left:
            time.sleep(1)
            return self._detect_device(attempts_left - 1)
        else:
            raise usb.core.USBError('Device not connected')

    def _configure_and_open_device(self):
        device, is_acc_mode = self._detect_device()
        if is_acc_mode:
            self._product_id = device.idProduct
            self._vendor_id = device.idVendor
            return device
        else:
            # Validate version code.
            buf = device.ctrl_transfer(
                usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_IN,
                51, 0, 0, 2)
            # version comes as little endian short
            assert len(buf) == 2
            adk_version = unpack('<H', buf)[0]
            eprint('Adk version is %d' % adk_version)
            assert adk_version == 2

            # Enable 2 channel Audio
            assert(device.ctrl_transfer(
                usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
                58, 1, 0, None) == 0)

            # Send accessory information.
            for i, data in enumerate(
                    (MANUFACTURER, MODEL_NAME, DESCRIPTION, VERSION, URI, SERIAL_NUMBER)):
                assert(device.ctrl_transfer(
                    usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
                    52, 0, i, data) == len(data))

            # Put device into accessory mode.
            assert(device.ctrl_transfer(
                usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
                53, 0, 0, None) == 0)
            usb.util.dispose_resources(device)
            time.sleep(1)

        # Attempt to redetect device after it is placed in accessory mode
        attempts_left = 5
        while attempts_left:
            device, is_acc_mode = self._detect_device()
            if is_acc_mode:
                self._product_id = device.idProduct
                self._vendor_id = device.idVendor
                return device
            time.sleep(1)
            attempts_left -= 1
        raise usb.core.USBError('Device not configured')

    def _get_endpoints(self,):
        assert self._device
        configuration = self._device.get_active_configuration()
        interface = configuration[(0, 0)]

        def _first_out_endpoint(endpoint):
            return (usb.util.endpoint_direction(endpoint.bEndpointAddress)
                    == usb.util.ENDPOINT_OUT)

        def _first_in_endpoint(endpoint):
            return (usb.util.endpoint_direction(endpoint.bEndpointAddress)
                    == usb.util.ENDPOINT_IN)

        endpoint_out = usb.util.find_descriptor(
            interface, custom_match=_first_out_endpoint)
        endpoint_in = usb.util.find_descriptor(
            interface, custom_match=_first_in_endpoint)
        assert endpoint_out and endpoint_in
        eprint(endpoint_in)
        eprint(endpoint_out)
        return endpoint_out, endpoint_in

    def read(self, timeout=0):
        """TODO: docstring here """
        # TODO: read a header as well?  This parsing should really be done
        # in a callable object that can track header, size, etc
        assert self._device and self._endpoint_in

        try:
            data_bytes = self._endpoint_in.read(self._read_callback.packet_size,
                                                timeout=timeout)
        except usb.core.USBError as err:
            if err.errno == 110:  # Operation timed out.
                # TODO: timeout callback
                eprint('Read Timed out')
                return
            else:
                # TODO: error callback
                raise err

            self._read_callback(self, data_bytes)

    def write(self, data, timeout=0):
        """TODO: docstring here """
        assert(self._device and self._endpoint_out and data and
               isinstance(data, bytes))
        self._write_queue.put(data)

    def write_command(self, command, data):
        """
        Writes a command and its accompanying data to the device
        """

        size_bytes = pack('>I', len(data))
        header = command + size_bytes
        self._write_queue.put((header, data))

    def _write_thread(self):
        # TODO: I should rename this to _write_process, and I can't use
        # the shutdown method here.
        while self._is_writing:
            try:
                data = self._write_queue.get(timeout=1)
            except queue.Empty:
                continue
            else:
                try:
                    self._endpoint_out.write(data[0], timeout=2000) # header
                    self._endpoint_out.write(data[1], timeout=2000) # data
                except usb.core.USBError as err:
                    if err.errno == 110:  # Operation timed out
                        eprint("Write Timed Out")
                        continue
                    else:
                        raise err
                else:
                    # TODO: track total bytes written, if they don't match the
                    # length of the data sent I have a problem and I need to
                    # correct it
                    pass

    def close(self):
        """TODO: docstring here """
        assert self._device and self._endpoint_out
        # stop the write thread first so our exit signal isn't
        # caught up in the middle of another write
        self._read_callback.close()
        self._is_writing = False
        self._writer_thread.join()

        self.signal_app_exit()
        usb.util.dispose_resources(self._device)
        self._device = None
        self._endpoint_out = None
        self._endpoint_in = None

    def signal_app_exit(self):
        """
        Sends an exit command to the application.  This is necessary for
        Android to cleanly exit.
        """
        if self.app_connected:
            # TODO: this will be a command and length in the future
            exitstr = pack('>H', 0xFFFF)
            self._endpoint_out.write(exitstr, timeout=1000)
            self.app_connected = False

    def set_callback(self, callback):
        """TODO: docstring here """
        assert callback
        self._read_callback = callback

def main():
    """TODO: docstring here """

    def signal_handler(sig, stack):
        """
        Handles exit events.
        """
        global SHUTDOWN
        SHUTDOWN = True
        eprint('Exiting...')

    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, signal_handler)

    eprint('Accessory test started\n'
           'SIGINT (^C) / SIGTERM to exit\n')

    write_q = Queue(30)
    usb_reader = USBReader(write_q)

    while not SHUTDOWN:
        try:
            with AndroidAccessory(callback_obj=usb_reader, write_queue=write_q) as accessory:
                while not SHUTDOWN:
                    # read with a 10 second timeout
                    accessory.read(10000)
        except usb.core.USBError as val:
            eprint(val.args)
            eprint('USBError occurred. Restarting…')
            time.sleep(1)

if __name__ == '__main__':
    main()
