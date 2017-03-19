""" Module Docstring here
TODO: I think this module works in linux.  I need to write an android app and Test
it.
"""
#! python3
# android_accessory_test.py
#

# pylint: disable=no-member
# pylint: disable=W0511,W0622,W0613,W0603,R0902

from struct import pack, unpack
import time
import signal
import threading
import os
from multiprocessing import Process
import socket
import select
from bitstring import  BitStream
import usb1
from constants import *

SHUTDOWN = False

class AndroidAccessory(object):
    """docstring for AndroidAccessory."""
    def __init__(self, usb_context, vendor_id=None, product_id=None):
        self.app_connected = False
        self._context = usb_context
        isconfigured, self._handle = self._find_handle(vendor_id, product_id)

        if isconfigured:
            print("Device is in accessory mode")
            # TODO: should I reset the device?
            # self._handle.claimInterface(0)
            # self._handle.resetDevice()
            # time.sleep(2)
            # isconfigured, self._handle = self._find_handle(vendor_id, product_id)
            # self._handle = self._configure_accessory_mode()
        else:
            self._handle = self._configure_accessory_mode()

        self._handle.claimInterface(0)

        # pause for one second so the android device can react to changes
        time.sleep(1)

        device = self._handle.getDevice()
        config = device[0]
        interface = config[0]
        self._in_endpoint, self._out_endpoint = self._get_endpoints(interface[0])
        if self._in_endpoint is None or self._out_endpoint is None:
            self._handle.releaseInterface(0)
            raise usb1.USBError(
                'Unable to retreive endpoints for accessory device'
            )

        self._is_running = True
        self._accessory_read_thread = threading.Thread(target=self._accessory_read_thread_proc)
        self._accessory_read_thread.start()
        self._sio_client_socket = socket.socket()
        self._sio_read_thread = None
        self._socket_connected = False

    def _find_handle(self, vendor_id=None, product_id=None, attempts_left=5):
        handle = None
        found_pid = None
        for device in self._context.getDeviceList():
            if vendor_id and product_id:
                # match by vendor and product id
                if (device.getVendorID() == vendor_id and
                        device.getProductID == product_id):
                    try:
                        handle = device.open()
                        found_vid = vendor_id
                        found_pid = device.getProductID()
                    except usb1.USBError as err:
                        print(err.args)
                    break
            elif device.getVendorID() in COMPATIBLE_VIDS:
                # attempt to get the first compatible vendor id
                try:
                    handle = device.open()
                    found_vid = device.getVendorID()
                    found_pid = device.getProductID()
                except usb1.USBError as err:
                    print(err.args)
                break

        if handle:
            print('Found {0:x}:{1:x}'.format(found_vid, found_pid))
            if found_pid in ACCESSORY_PID:
                return True, handle
            else:
                return False, handle
        elif attempts_left:
            time.sleep(1)
            return self._find_handle(vendor_id, product_id, attempts_left-1)
        else:
            raise usb1.USBError('Device not connected')

    def _configure_accessory_mode(self):
        # Don't need to claim interface to do control read/write, and the
        # original driver prevents it
        # self._handle.claimInterface(0)

        version = self._handle.controlRead(
            usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
            51, 0, 0, 2
        )

        adk_ver = unpack('<H', version)[0]
        print("ADK version is: %d" % adk_ver)
        assert adk_ver == 2


        # enter accessory information
        for i, data in enumerate((MANUFACTURER, MODEL_NAME, DESCRIPTION,
                                  VERSION, URI, SERIAL_NUMBER)):
            assert self._handle.controlWrite(
                usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
                52, 0, i, data.encode()
            ) == len(data)


        # enable 2 channel audio
        assert self._handle.controlWrite(
            usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
            58, 1, 0, b''
        ) == 0

        # start device in accessory mode
        self._handle.controlWrite(
            usb1.TYPE_VENDOR | usb1.RECIPIENT_DEVICE,
            53, 0, 0, b''
        )

        time.sleep(1)

        isconfigured, newhandle = self._find_handle()
        if isconfigured:
            return newhandle
        else:
            raise usb1.USBError('Error configuring accessory mode')

    def _get_endpoints(self, interface):
        inep = None
        outep = None
        for endpoint in interface:
            addr = endpoint.getAddress()
            if (addr & 0x80) == 0x80:
                inep = addr
                print('In endpoint address: %02x' % addr)
            elif (addr & 0x80) == 0x00:
                outep = addr
                print('Out endpoint address: %02x' % addr)
        return inep, outep

    def _socket_read_thread_proc(self):
        # TODO: may want to add a callback for writes
        while self._socket_connected:
            try:
                r, w, x = select.select([self._sio_client_socket.fileno()], [], [])
                if r:
                    data = self._sio_client_socket.recv(16384)
                else:
                    continue
            except InterruptedError:
                continue
            except EOFError:
                eprint('Socket disconnected by server')
                self.stop()
            else:
                self._handle.bulkWrite(self._out_endpoint, data)

    def _accessory_read_thread_proc(self):
        while self._is_running:
            try:
                data = self._handle.bulkRead(self._in_endpoint, 16384)
            except usb1.USBError as err:
                # TODO: I should only continue if the error is a timeout error
                self.stop()
            else:
                if self._socket_connected:
                    r, w, x = select.select([], [self._sio_client_socket.fileno()], [])
                    if w:
                        self._sio_client_socket.sendall(data)
                else:
                    if len(data) == 2:
                        command = bytes(data)
                        if command == CMD_ACCESSORY_CONNECTED:
                            eprint("Accessory Connected")
                            self.app_connected = True
                        elif command == CMD_CONNECT_SOCKET:
                            self.connect_sio_socket()
                        else:
                            eprint("Unknown Command:")
                            eprint(command)
                    else:
                        eprint("Incorrect data size: %d" % len(data))

    def stop(self):
        """
        TODO: Docstring
        """
        self.disconnect_sio_socket()
        self.signal_app_exit()
        # give one second for transfers to complete
        time.sleep(1)
        self._is_running = False
        if self._handle:
            self._handle.releaseInterface(0)
            self._handle.close()

    def connect_sio_socket(self):
        eprint("Connecting to Socket")
        self._sio_client_socket.connect(('localhost', 8000))
        if self._sio_client_socket:
            eprint("Socket Connected")
            self._socket_connected = True
            self._sio_read_thread = threading.Thread(target=self._socket_read_thread_proc)
            self._sio_read_thread.start()
            return True
        else:
            eprint("Unable to connect to socket")
            return False

    def disconnect_sio_socket(self):
        if self._socket_connected:
            eprint("Disconnecting socket")
            self._socket_connected = False
            self._sio_client_socket.close()

    def signal_app_exit(self):
        """
        Sends an exit command to the application.  This is necessary for
        Android to cleanly exit.
        """
        if self.app_connected and not self._socket_connected:
            # TODO: this will be a command and length in the future
            self._handle.bulkWrite(self._out_endpoint, CMD_CLOSE_ACCESSORY)
            self.app_connected = False

class AccessoryProcess(Process):
    """
    Process that forwards a socket connection over USB via Android
    Accessory Protocol
    """
    def __init__(self, accessory_pipe):
        super(AccessoryProcess, self).__init__()
        self._is_running = True
        self._comm_pipe = accessory_pipe
        self._accessory = None

    def _exit(self):
        print('Exiting...')
        if self._accessory is not None:
            self._accessory.stop()
        self._is_running = False
        self._comm_pipe.close()

    def run(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            signal.signal(signum, self._exit)

        with usb1.USBContext() as context:
            while self._is_running:
                try:
                    self._accessory = AndroidAccessory(context)
                except usb1.USBError as val:
                    eprint('Unable to open accessory:')
                    eprint(val.args)
                    time.sleep(1)
                    continue
                else:
                    while self._is_running:
                        try:
                            command = self._comm_pipe.recv()
                        except EOFError:
                            self._exit()
                        else:
                            if command == 'DISCONNECT':
                                self._accessory.disconnect_sio_socket()
                            elif command == 'EXIT':
                                self._exit()
