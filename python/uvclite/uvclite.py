

import libuvc
from ctypes import byref, c_int, sizeof, POINTER, \
    cast, c_uint8, c_uint16, c_ubyte, c_void_p, cdll, addressof, \
    c_char

__author__ = 'Eric Callahan'

__all__ = [
    'UVCError', 'UVCFrame', 'UVCDevice', 'UVCContext'
]

class UVCError(IOError):
    def __init__(self, strerror, errnum=None):
        IOError.__init__(self, errnum, strerror)

def _get_uvc_error(errcode):
    try:
        strerr = libuvc.uvc_strerror(errcode).decode('utf8')
    except AttributeError:
        strerr = libuvc.str_error_map[errcode]
    errnum = libuvc.libuvc_errno_map[errcode]
    return UVCError(strerr, errnum)

class UVCFrame(object):
    def __init__(self, frame_p):
        self.frame = frame_p.contents
        self.size = self.frame.data_bytes
        self.width = self.frame.width
        self.height = self.frame.height
        self.data = libuvc.buffer_at(self.frame.data, self.size)


class UVCDevice(object):
    def __init__(self, dev_p, new_ref=False):
        self._device_p = dev_p
        self._handle_p = c_void_p()
        self._stream_handle_p = None
        self._new_ref = new_ref
        self._is_open = False
        self._stream_ctrl = libuvc.uvc_stream_ctrl()
        self._format_set = False
        self._frame_callback = libuvc.uvc_null_frame_callback

    def open(self):
        if not self._is_open:
            if self._new_ref:
                libuvc.uvc_ref_device(self._device_p)

            ret = libuvc.uvc_open(self._device_p, byref(self._handle_p))
            if ret != libuvc.UVC_SUCCESS:
                raise _get_uvc_error(ret)
            self._is_open = True

    def close(self):
        if self._is_open:
            libuvc.uvc_close(self._handle_p)
            self._is_open = False
        libuvc.uvc_unref_device(self._device_p)

    def set_stream_format(self, frame_format=libuvc.UVC_FRAME_FORMAT_MJPEG,
                          width=640, height=480, frame_rate=30):
        ret = libuvc.uvc_get_stream_ctrl_format_size(
            self._handle_p, byref(self._stream_ctrl), frame_format, width,
            height, frame_rate)

        if ret != libuvc.UVC_SUCCESS:
            raise _get_uvc_error(ret)
        self._format_set = True

    def set_callback(self, callback):
        if not callback:
            self._frame_callback = libuvc.uvc_null_frame_callback
        else:
            def _frame_cb(frame, user_id):
                new_frame = UVCFrame(frame)
                callback(new_frame, user_id)

            self._frame_callback = libuvc.uvc_frame_callback(_frame_cb)

    def start_streaming(self, user_id=None):
        if not self._format_set:
            # if the format hasn't been set, use default values
            self.set_stream_format()

        # don't open a stream if we are already streaming
        if not self._stream_handle_p:
            # open the stream.  Polling mode if callback is not supplied
            self._stream_handle_p = c_void_p()
            ret = libuvc.uvc_stream_open_ctrl(self._handle_p, byref(self._stream_handle_p),
                                              byref(self._stream_ctrl))

            if ret != libuvc.UVC_SUCCESS:
                raise _get_uvc_error(ret)

            ret = libuvc.uvc_stream_start(self._stream_handle_p, self._frame_callback,
                                          user_id, 0)
            if ret != libuvc.UVC_SUCCESS:
                raise _get_uvc_error(ret)

    def stop_streaming(self):
        # Dont close unless we are streaming
        if self._stream_handle_p:
            libuvc.uvc_stream_stop(self._stream_handle_p)
            libuvc.uvc_stream_close(self._stream_handle_p)
            self._stream_handle_p = None

    def get_frame(self, timeout=1000000):
        """
        Timeout is in microseconds, defaults to 1 second timeout.
        Set timeout to 0 to block indefinitely, -1 to return immediately.

        If this is called when a callback has been set, an UVC Error will
        be raised.
        """
        if self._stream_handle_p:
            frame = libuvc.uvc_frame_p()
            ret = libuvc.uvc_stream_get_frame(self._stream_handle_p, byref(frame), timeout)
            if ret != libuvc.UVC_SUCCESS:
                if ret == libuvc.UVC_ERROR_TIMEOUT:
                    print("Attempt to read frame timed out")
                else:
                    raise _get_uvc_error(ret)

            return UVCFrame(frame)


# TODO: Make this derive from sequence and implement it that way
class _DeviceIterator(object):
    def __init__(self, dev_list_p):
        self._device_list_p = dev_list_p
        self._device_count = 0

        # get device count.  The uvc get device list function
        # does not return a count, so I assume it is null terminated
        while True:
            if self._device_list_p[self._device_count]:
                self._device_count += 1
            else:
                break

    def __iter__(self):
        for i in range(self._device_count):
            # return a UVC device with a new reference?
            yield UVCDevice(self._device_list_p[i], True)

class UVCContext(object):
    def __init__(self):
        self._context_p = c_void_p()
        self._device_list_p = None

        # Retreive uvc context.
        ret = libuvc.uvc_init(byref(self._context_p), None)
        if ret != libuvc.UVC_SUCCESS:
            raise _get_uvc_error(ret)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def close(self):
        """

        Releases UVC Context and frees up resources

        """
        if self._device_list_p:
            # free device list if it exists
            libuvc.uvc_free_device_list(self._device_list_p, 1)
            self._device_list_p = None

        if self._context_p:
            libuvc.uvc_exit(self._context_p)
            self._context_p = None

    def find_device(self, vendor_id=0, product_id=0, serial_number=None):
        """
        Attempts to find a device.  Returns the first UVC device found
        matching the given parameters.

        Params:
        vendor_id   Usb Vendor Id of the device to find. (integer)
        product_id  Usb Product Id of the the device to find (integer)
        serial_number Serial Number of the device to find (string)

        Returns:
        UVCDevice object

        Usage:
        All parameters are optional.  If any parameter is left out,
        the first device found matching the remaining parmeters will
        be returned.  If no device is found this function will raise
        a UVCError exception.

        Examples:
        Find the first uvc device on the System:
        context.find_device()

        Find device matching product id 0x1234:
        context.find_device(product_id=0x1234)
        """
        dev_p = c_void_p()
        if serial_number:
            sn = serial_number.encode('utf-8')
            ret = libuvc.uvc_find_device(self._context_p, byref(dev_p),
                                         vendor_id, product_id, sn)
        else:
            ret = libuvc.uvc_find_device(self._context_p, byref(dev_p),
                                         vendor_id, product_id, None)

        if ret != libuvc.UVC_SUCCESS:
            raise _get_uvc_error(ret)

        return UVCDevice(dev_p)

    def get_device_list(self):
        """
        Retreives a list of UVC devices on the system.  If a device list has
        already been allocated, it will first be freed

        """
        if self._device_list_p:
            libuvc.uvc_free_device_list(self._device_list_p, 1)

        self._device_list_p = POINTER(c_void_p)()
        ret = libuvc.uvc_get_device_list(self._context_p, byref(self._device_list_p))
        if ret != libuvc.UVC_SUCCESS:
            raise _get_uvc_error(ret)

        return _DeviceIterator(self._device_list_p)
