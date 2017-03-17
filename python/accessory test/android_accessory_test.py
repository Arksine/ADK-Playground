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
from multiprocessing import Queue, Pipe
import queue
from bitstring import  BitStream
import usb1
from uvcprocess import UVCProcess
from constants import *

SHUTDOWN = False

# TODO: create function to easily convert int32 to big endian bytearray

class ReadCallback(object):
    """
    TODO: Docstring
    """
    HEADER_SIZE = 6  # header size in bytes

    def __init__(self, acc, write_queue):
        self._accessory = acc
        self._buffer = None
        self._is_header = True
        self._command = None
        self._packet_size = self.HEADER_SIZE

        self._write_queue = write_queue
        self._uvc_process = None
        self._uvc_pipe = None
        self._uvc_child_pipe = None

    def __call__(self, transfer):
        """
        TODO: Docstring
        """
        length = transfer.getActualLength()
        if not length:
            return True
        data = transfer.getBuffer()[:length]

        if not self._buffer:
            self._buffer = BitStream(data)
        else:
            self._buffer += BitStream(data)

        return self._parse_packet()

    def _parse_packet(self):
        """
        TODO: Docstring
        """
        while len(self._buffer) >= (self._packet_size * 8):
            if self._is_header:
                self._command = self._buffer.read('bytes:2')
                self._packet_size = self._buffer.read('uintbe:32')
                print("Packet size %d:" % self._packet_size)
                del self._buffer[:self.HEADER_SIZE * 8]
                self._buffer.pos = 0
                if self._packet_size == 0:
                    self._packet_size = self.HEADER_SIZE
                    return self._execute_command(None)
                else:
                    self._is_header = False

            else:
                parse_token = 'bytes:{0}'.format(self._packet_size)
                data = self._buffer.read(parse_token)
                del self._buffer[:(self._packet_size * 8)]
                self._buffer.pos = 0
                self._is_header = True
                return self._execute_command(data)

        return True

    def _execute_command(self, data):
        if self._command == CMD_TERMINATE:
            print('Terminate Command Received')
            global SHUTDOWN
            SHUTDOWN = True
            # TODO: need to do this outside of the transfer
            stop_acc_thread = threading.Thread(target=self._accessory.stop)
            stop_acc_thread.start()
            return False
        elif self._command == CMD_EXIT:
            print('Exit app Command Received')
            self._accessory.signal_app_exit()
        elif self._command == CMD_APP_CONNECTED:
            print('Android Application Connected')
            self._accessory.app_connected = True
        elif self._command == CMD_CAM_START:
            self._start_uvc_process()
        elif self._command == CMD_CAM_STOP:
            # stop the uvc process in another thread so this one isn't blocked
            stop_uvc_thread = threading.Thread(target=self._stop_uvc_thread_proc)
            stop_uvc_thread.start()
        elif self._command == CMD_TEST:
            # Test simply takes a short, parses it, and echoes it
            # back to the device
            assert len(data) == 2
            value = unpack('>H', data)[0]
            print('Read in value: %d', value)
            value += 10
            out = pack('>H', value)
            self._accessory.write_command(CMD_TEST, out)

        return True

    def _start_uvc_process(self):
        if not self._uvc_process:
            print("Starting UVC Process")
            self._uvc_pipe, self._uvc_child_pipe = Pipe()
            self._uvc_process = UVCProcess(self._write_queue, self._uvc_child_pipe)
            self._uvc_process.start()

    def _stop_uvc_thread_proc(self):
        if self._uvc_process:
            print("Stopping UVC Process")
            self._uvc_pipe.send('STOP')
            self._uvc_process.join(1)
            if self._uvc_process.is_alive():
                self._uvc_process.terminate()
            self._uvc_process = None

class AndroidAccessory(object):
    """docstring for AndroidAccessory."""
    def __init__(self, usb_context, write_queue, vendor_id=None, product_id=None):
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

        read_callback = usb1.USBTransferHelper()
        callback_obj = ReadCallback(self, write_queue)
        read_callback.setEventCallback(
            usb1.TRANSFER_COMPLETED,
            callback_obj,
        )

        self._write_list = []
        for _ in range(10):
            writer = self._handle.getTransfer()
            self._write_list.append(writer)

        self._read_list = []
        for _ in range(64):
            data_reader = self._handle.getTransfer()
            data_reader.setBulk(
                self._in_endpoint,
                0x4000,
                callback=read_callback,
            )
            data_reader.submit()
            self._read_list.append(data_reader)

        self._write_queue = write_queue
        self._is_running = True
        self._write_thread = threading.Thread(target=self._write_thread_proc)
        self._write_thread.start()

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

    def run(self):
        """
        TODO: Docstring
        """

        try:
            while any(x.isSubmitted() for x in self._write_list) or \
                    any(x.isSubmitted() for x in self._read_list):
                try:
                    self._context.handleEvents()
                except usb1.USBErrorInterrupted:
                    pass
                if not self._is_running:
                    break
        finally:
            self._handle.releaseInterface(0)

    def stop(self):
        """
        TODO: Docstring
        """
        self._is_running = False
        self.signal_app_exit()
        # give two seconds for transfers to complete
        time.sleep(2)
        for xlist in (self._write_list, self._read_list):
            for xfer in xlist:
                if xfer.isSubmitted():
                    xfer.cancel()

    def _write_thread_proc(self):
        # TODO: may want to add a callback for writes
        while self._is_running:
            try:
                data = self._write_queue.get(timeout=1)
            except queue.Empty:
                continue
            else:
                for packet in data:
                    transfer_submitted = False
                    for xfer in self._write_list:
                        if not xfer.isSubmitted():
                            xfer.setBulk(
                                self._out_endpoint,
                                packet,
                            )
                            xfer.submit()
                            transfer_submitted = True
                            break

                    if not transfer_submitted:
                        new_xfer = self._handle.getTransfer()
                        self._write_list.append(new_xfer)
                        new_xfer.setBulk(
                            self._out_endpoint,
                            packet,
                        )
                        new_xfer.submit()

    def write_command(self, command, data):
        """
        TODO: Docstring
        """

        if not data:
            # send command with data length of zero
            header = command + b'\x00\x00\x00\x00'
            self._write_queue.put((header,))
        else:
            size = len(data)
            size_bytes = pack('>I', size)
            header = command + size_bytes
            self._write_queue.put((header, data))

    def signal_app_exit(self):
        """
        Sends an exit command to the application.  This is necessary for
        Android to cleanly exit.
        """
        if self.app_connected:
            # TODO: this will be a command and length in the future
            self.write_command(CMD_EXIT, None)
            self.app_connected = False

def setup_signal_exit(acc):
    """
    TODO: Docstring
    """
    def quit(sig, stack):
        """
        TODO: Docstring
        """
        print('Exiting...')
        if acc is not None:
            acc.stop()
        global SHUTDOWN
        SHUTDOWN = True

    signal.signal(signal.SIGINT, quit)
    signal.signal(signal.SIGTERM, quit)

def main():
    """
    TODO: Docstring
    """
    # TODO: listen for connected devices, also allow device Pid to be passed
    # as argument
    write_q = Queue()
    with usb1.USBContext() as context:
        while not SHUTDOWN:
            try:
                accessory = AndroidAccessory(context, write_queue=write_q)
                setup_signal_exit(accessory)
            except usb1.USBError as val:
                print('Unable to open accessory:')
                print(val.args)
                time.sleep(1)
                continue

            print('Accessory test started\n'
                  'SIGINT (^C) / SIGTERM to exit\n')

            accessory.run()

if __name__ == '__main__':
    main()
