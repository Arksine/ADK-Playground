#!/usr/bin/python3
# coding=utf-8
"""pyusb_accessory.py

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
from multiprocessing import Process
import socket
import select
import threading
import usb
from constants import *

SHUTDOWN = False

def eprint(*args, **kwargs):
    """
    Simple helper to print to stderr.  Should work python 2 an 3.
    """
    print(*args, file=sys.stderr, **kwargs)


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
    def __init__(self, callback_obj, vendor_id=None, product_id=None):
        self.app_connected = False
        self._read_callback = callback_obj

        self._vendor_id = vendor_id
        self._product_id = product_id
        self._device = self._configure_and_open_device()
        self._endpoint_out, self._endpoint_in = self._get_endpoints()
        

        self._sio_client_socket = None
        self._accessory_connected = False
        self._socket_connected = False

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
            # Reset device and attempt to redetect, we do not want
            # device in accessory mode on first run
            device.reset()
            time.sleep(2)
            device, is_acc_mode = self._detect_device()

        # Configure device for android accessory
        if not is_acc_mode:
            # Validate version code.
            buf = device.ctrl_transfer(
                usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_IN,
                51, 0, 0, 2)
            # version comes as little endian short
            assert len(buf) == 2
            adk_version = unpack('<H', buf)[0]
            eprint('Adk version is %d' % adk_version)
            assert adk_version == 2

            # Send accessory information.
            for i, data in enumerate(
                    (MANUFACTURER, MODEL_NAME, DESCRIPTION, VERSION, URI, SERIAL_NUMBER)):
                assert(device.ctrl_transfer(
                    usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
                    52, 0, i, data) == len(data))

            # Enable 2 channel Audio
            assert(device.ctrl_transfer(
                usb.util.CTRL_TYPE_VENDOR | usb.util.CTRL_OUT,
                58, 1, 0, None) == 0)

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

    def _connect_socket(self):
        self._sio_client_socket = socket.create_connection(('127.0.0.1', 8000))
        if self._sio_client_socket:
            self._socket_connected = True
            # TODO: start socket read thread


    def _socket_read_thread_proc(self, timeout=0):
        pass

    def _accessory_read_thread_proc(self, timeout=0):
        self._accessory_connected = True
        while self._accessory_connected:
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


    def close(self):
        """TODO: docstring here """
        if self._device:
            # stop the write thread first so our exit signal isn't
            # caught up in the middle of another writ
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
            self._endpoint_out.write(CMD_CLOSE_ACCESSORY, timeout=1000)
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
