# Copyright (C) 2017 Eric Callahan
# TODO: add license, code taken from libusb1 and pyusb, credit them

# pylint: disable=no-member
# pylint: disable=W0511,W0622,W0613,W0603,R0902,C0103,W0614,W0401,W0212,R0903

from ctypes import *
import errno
import os, sys, inspect

__author__ = 'Eric Callahan'


def buffer_at(address, length):
    """
    Similar to ctypes.string_at, but zero-copy and requires an integer address.
    """
    return bytearray((c_char * length).from_address(address))

# TODO: create function for more robust library loading, that should hopefully
# work on all platforms
_libuvc = CDLL('libuvc.so')

# libuvc.h

# enum uvc_error (uvc_error_t) return codes
UVC_SUCCESS = 0
UVC_ERROR_IO = -1
UVC_ERROR_INVALID_PARAM = -2
UVC_ERROR_ACCESS = -3
UVC_ERROR_NO_DEVICE = -4
UVC_ERROR_NOT_FOUND = -5
UVC_ERROR_BUSY = -6
UVC_ERROR_TIMEOUT = -7
UVC_ERROR_OVERFLOW = -8
UVC_ERROR_PIPE = -9
UVC_ERROR_INTERRUPTED = -10
UVC_ERROR_NO_MEM = -11
UVC_ERROR_NOT_SUPPORTED = -12
UVC_ERROR_INVALID_DEVICE = -50
UVC_ERROR_INVALID_MODE = -51
UVC_ERROR_CALLBACK_EXISTS = -52
UVC_ERROR_OTHER = -99

# map return codes to string messages
str_error_map = {
    UVC_SUCCESS:'Success (no error)',
    UVC_ERROR_IO:'Input/output error',
    UVC_ERROR_INVALID_PARAM:'Invalid parameter',
    UVC_ERROR_ACCESS:'Access denied',
    UVC_ERROR_NO_DEVICE:'No such device',
    UVC_ERROR_NOT_FOUND:'Not found',
    UVC_ERROR_BUSY:"Resource Busy",
    UVC_ERROR_TIMEOUT:'Operation timed out',
    UVC_ERROR_OVERFLOW:'Overflow',
    UVC_ERROR_PIPE:'Pipe Error',
    UVC_ERROR_INTERRUPTED:'System call interrupted (perhaps due to signal)',
    UVC_ERROR_NO_MEM:'Insufficient memory',
    UVC_ERROR_NOT_SUPPORTED:'Operation not supported or unimplemented on this platform',
    UVC_ERROR_INVALID_DEVICE:'Invalid device',
    UVC_ERROR_INVALID_MODE:'Invalid mode',
    UVC_ERROR_CALLBACK_EXISTS:'Callback exists, cannot poll',
    UVC_ERROR_OTHER:'Unknown Error'
}

# map return codes to error numbers
libuvc_errno_map = {
    0:None,
    UVC_ERROR_IO:errno.__dict__.get('EIO', None),
    UVC_ERROR_INVALID_PARAM:errno.__dict__.get('EINVAL', None),
    UVC_ERROR_ACCESS:errno.__dict__.get('EACCES', None),
    UVC_ERROR_NO_DEVICE:errno.__dict__.get('ENODEV', None),
    UVC_ERROR_NOT_FOUND:errno.__dict__.get('ENOENT', None),
    UVC_ERROR_BUSY:errno.__dict__.get('EBUSY', None),
    UVC_ERROR_TIMEOUT:errno.__dict__.get('ETIMEDOUT', None),
    UVC_ERROR_OVERFLOW:errno.__dict__.get('EOVERFLOW', None),
    UVC_ERROR_PIPE:errno.__dict__.get('EPIPE', None),
    UVC_ERROR_INTERRUPTED:errno.__dict__.get('EINTR', None),
    UVC_ERROR_NO_MEM:errno.__dict__.get('ENOMEM', None),
    UVC_ERROR_NOT_SUPPORTED:errno.__dict__.get('EOPNOTSUPP', None),
    UVC_ERROR_INVALID_DEVICE:errno.__dict__.get('EBADSLT', None),
    UVC_ERROR_INVALID_MODE:errno.__dict__.get('EBADR', None),
    UVC_ERROR_CALLBACK_EXISTS:errno.__dict__.get('EBADE', None),
    UVC_ERROR_OTHER:None
}

# enum uvc_frame_format
UVC_FRAME_FORMAT_UNKNOWN = 0
UVC_FRAME_FORMAT_ANY = 0
UVC_FRAME_FORMAT_UNCOMPRESSED = 1
UVC_FRAME_FORMAT_COMPRESSED = 2
UVC_FRAME_FORMAT_YUYV = 3
UVC_FRAME_FORMAT_UYVY = 4
UVC_FRAME_FORMAT_RGB = 5
UVC_FRAME_FORMAT_BGR = 6
UVC_FRAME_FORMAT_MJPEG = 7
UVC_FRAME_FORMAT_GRAY8 = 8
UVC_FRAME_FORMAT_BY8 = 9
UVC_FRAME_FORMAT_COUNT = 10

# enum uvc_vs_des_subtype
UVC_VS_UNDEFINED = 0x00
UVC_VS_INPUT_HEADER = 0x01
UVC_VS_OUTPUT_HEADER = 0x02
UVC_VS_STILL_IMAGE_FRAME = 0x03
UVC_VS_FORMAT_UNCOMPRESSED = 0x04
UVC_VS_FRAME_UNCOMPRESSED = 0x05
UVC_VS_FORMAT_MJPEG = 0x06
UVC_VS_FRAME_MJPEG = 0x07
UVC_VS_FORMAT_MPEG2TS = 0x0a
UVC_VS_FORMAT_DV = 0x0c
UVC_VS_COLORFORMAT = 0x0d
UVC_VS_FORMAT_FRAME_BASED = 0x10
UVC_VS_FRAME_FRAME_BASED = 0x11
UVC_VS_FORMAT_STREAM_BASED = 0x12

# IMPORTANT: C enums are mapped to c_int in python

class _format_union(Union):
    _fields_ = [('guidFormat', ARRAY(c_uint8, 16)),
                ('fourccFormat', ARRAY(c_uint8, 4))]

class _bit_union(Union):
    _fields_ = [('bBitsPerPixel', c_uint8),
                ('bmFlags', c_uint8)]

class uvc_format_desc(Structure):
    pass
uvc_format_desc_p = POINTER(uvc_format_desc)

class uvc_frame_desc(Structure):
    pass
uvc_frame_desc_p = POINTER(uvc_frame_desc)

uvc_frame_desc._fields_ = [('parent', uvc_format_desc_p),
                           ('prev', uvc_frame_desc_p),
                           ('next', uvc_frame_desc_p),
                           ('bDescriptorSubtype', c_int), #TODO: not sure if this is c_uint8 or
                                                          # c_int - enum uvc_vs_desc_subtype
                           ('bFrameIndex', c_uint8),
                           ('bmCapabilities', c_uint8),
                           ('wWidth', c_uint16),
                           ('wHeight', c_uint16),
                           ('dwMinBitRate', c_uint32),
                           ('dwMaxBitRate', c_uint32),
                           ('dwMaxVideoFrameBufferSize', c_uint32),
                           ('dwDefaultFrameInterval', c_uint32),
                           ('dwMinFrameInterval', c_uint32),
                           ('dwMaxFrameInterval', c_uint32),
                           ('dwFrameIntervalStep', c_uint32),
                           ('bFrameIntervalType', c_uint8),
                           ('dwBytesPerLine', c_uint32),
                           ('intervals', POINTER(c_uint32))]

uvc_format_desc._anonymous_ = ('fmt', 'bit',)
uvc_format_desc._fields_ = [('parent', c_void_p),
                            ('prev', uvc_format_desc_p),
                            ('next', uvc_format_desc_p),
                            ('bDescriptorSubtype', c_int),      # enum uvc_vs_desc_subtype
                            ('bFormatIndex', c_uint8),
                            ('bNumFrameDescriptors', c_uint8),
                            ('fmt', _format_union),
                            ('bit', _bit_union),
                            ('bDefaultFrameIndex', c_uint8),
                            ('bAspectRatioX', c_uint8),
                            ('bAspectRatioY', c_uint8),
                            ('bmInterlaceFlags', c_uint8),
                            ('bCopyProtect', c_uint8),
                            ('bVariableSize', c_uint8),
                            ('frame_descs', uvc_frame_desc_p)]

# enum_uvc_req_code
UVC_RC_UNDEFINED = 0x00
UVC_SET_CUR = 0x01
UVC_GET_CUR = 0x81
UVC_GET_MIN = 0x82
UVC_GET_MAX = 0x83
UVC_GET_RES = 0x84
UVC_GET_LEN = 0x85
UVC_GET_INFO = 0x86
UVC_GET_DEF = 0x87

# enum uvc_device_power_mode
UVC_VC_VIDEO_POWER_MODE_FULL = 0x000b
UVC_VC_VIDEO_POWER_MODE_DEVICE_DEPENDENT = 0x001b

# TODO:
# enum uvc_ct_ctrl_selector
# enum uvc_pu_ctrl_selector
# enum uvc_term_type
# enum uvc_it_type
# enum_uvc_ot_type
# enum_uvc_et_type
# struct uvc_input_terminal
# struct uvc_output_terminal
# struct uvc_processing_unit
# struct_uvc_extension_unit
# enum uvc_status_class
# enum uvc_status_attribute
# status callback
# button callback

# struct uvc_device_descriptor
class uvc_device_descriptor(Structure):
    _fields_ = [('idVendor', c_uint16),
                ('idProduct', c_uint16),
                ('bcdUVC', c_uint16),
                ('serialNumber', c_char_p),
                ('manufacturer', c_char_p),
                ('product', c_char_p)]

class _timeval(Structure):
    _fields_ = [('tv_sec', c_long),  #this is actually time_t, I assume its a long on linux
                ('tv_usec', c_long)]

class uvc_frame(Structure):
    _fields_ = [('data', c_void_p),
                ('data_bytes', c_size_t),
                ('width', c_uint32),
                ('height', c_uint32),
                ('frame_format', c_int),
                ('step', c_size_t),
                ('sequence', c_uint32),
                ('capture_time', _timeval),
                ('source', c_void_p),
                ('library_owns_data', c_uint8)]
uvc_frame_p = POINTER(uvc_frame)
#  typedef void(uvc_frame_callback_t)(struct uvc_frame *frame, void *user_ptr);
uvc_frame_callback = CFUNCTYPE(None, POINTER(uvc_frame), c_void_p)
uvc_null_frame_callback = cast(None, uvc_frame_callback)

class uvc_stream_ctrl(Structure):
    _fields_ = [('bmHint', c_uint16),
                ('bFormatIndex', c_uint8),
                ('bFrameIndex', c_int8),
                ('dwFrameInterval', c_int32),
                ('wKeyFrameRate', c_uint16),
                ('wPFrameRate', c_uint16),
                ('wCompQuality', c_uint16),
                ('wCompWindowSize', c_uint16),
                ('wDelay', c_uint16),
                ('dwMaxVideoFrameSize', c_uint32),
                ('dwMaxPayloadTransferSize', c_uint32),
                ('dwClockFrequency', c_uint32),
                ('bmFramingInfo', c_uint8),
                ('bPreferredVersion', c_uint8),
                ('bMinVersion', c_uint8),
                ('bMaxVersion', c_uint8),
                ('bInterfaceNumber', c_uint8)]

### Function prototypes###

# uvc_error_t uvc_init(uvc_context_t **ctx, struct libusb_context *usb_ctx);
uvc_init = _libuvc.uvc_init
uvc_init.argtypes = [POINTER(c_void_p), c_void_p]

# void uvc_exit(uvc_context_t *ctx);
uvc_exit = _libuvc.uvc_exit
uvc_exit.argtypes = [c_void_p]
uvc_exit.restype = None

# uvc_error_t uvc_get_device_list(uvc_context_t *ctx,
#                                 uvc_device_t ***list);
uvc_get_device_list = _libuvc.uvc_get_device_list
uvc_get_device_list.argtypes = [c_void_p, POINTER(POINTER(c_void_p))]

# void uvc_free_device_list(uvc_device_t **list, uint8_t unref_devices);
uvc_free_device_list = _libuvc.uvc_free_device_list
uvc_free_device_list.argtypes = [POINTER(c_void_p), c_uint8]
uvc_free_device_list.restype = None

# uint8_t uvc_get_bus_number(uvc_device_t *dev);
uvc_get_bus_number = _libuvc.uvc_get_bus_number
uvc_get_bus_number.argtypes = [c_void_p]
uvc_get_bus_number.restype = c_uint8

# uint8_t uvc_get_device_address(uvc_device_t *dev);
uvc_get_device_address = _libuvc.uvc_get_device_address
uvc_get_device_address.argtypes = [c_void_p]
uvc_get_device_address.restype = c_uint8

# uvc_error_t uvc_find_device(uvc_context_t *ctx,
#                             uvc_device_t **dev,
#                             int vid,
#                             int pid,
#                             const char *sn);
uvc_find_device = _libuvc.uvc_find_device
uvc_find_device.argtypes = [
    c_void_p,
    POINTER(c_void_p),
    c_int,
    c_int,
    c_char_p
]

# uvc_error_t uvc_open(uvc_device_t *dev, uvc_device_handle_t **devh);
uvc_open = _libuvc.uvc_open
uvc_open.argtypes = [c_void_p, POINTER(c_void_p)]

# void uvc_close(uvc_device_handle_t *devh);
uvc_close = _libuvc.uvc_close
uvc_close.argtypes = [c_void_p]
uvc_close.restype = None

# uvc_device_t *uvc_get_device(uvc_device_handle_t *devh);
uvc_get_device = _libuvc.uvc_get_device
uvc_get_device.argtypes = [c_void_p]
uvc_get_device.restype = c_void_p

# libusb_device_handle *uvc_get_libusb_handle(uvc_device_handle_t *devh);
uvc_get_libusb_handle = _libuvc.uvc_get_libusb_handle
uvc_get_libusb_handle.argtypes = [c_void_p]
uvc_get_libusb_handle.restype = c_void_p

# void uvc_ref_device(uvc_device_t *dev);
uvc_ref_device = _libuvc.uvc_ref_device
uvc_ref_device.argtypes = [c_void_p]
uvc_ref_device.restype = None

# void uvc_unref_device(uvc_device_t *dev);
uvc_unref_device = _libuvc.uvc_unref_device
uvc_unref_device.argtypes = [c_void_p]
uvc_unref_device.restype = None

# TODO:
# void uvc_set_status_callback(uvc_device_handle_t *devh,
#                              uvc_status_callback_t cb,
#                              void *user_ptr);
# const uvc_input_terminal_t *uvc_get_input_terminals(uvc_device_handle_t *devh);
# const uvc_output_terminal_t *uvc_get_output_terminals(uvc_device_handle_t *devh);
# const uvc_processing_unit_t *uvc_get_processing_units(uvc_device_handle_t *devh);
# const uvc_extension_unit_t *uvc_get_extension_units(uvc_device_handle_t *devh);

# uvc_error_t uvc_get_stream_ctrl_format_size(uvc_device_handle_t *devh,
#                                             uvc_stream_ctrl_t *ctrl,
#                                             enum uvc_frame_format format,
#                                             int width,
#                                             int height,
#                                             int fps);
uvc_get_stream_ctrl_format_size = _libuvc.uvc_get_stream_ctrl_format_size
uvc_get_stream_ctrl_format_size.argtypes = [
    c_void_p,
    POINTER(uvc_stream_ctrl),
    c_int,
    c_int,
    c_int,
    c_int
]

# const uvc_format_desc_t *uvc_get_format_descs(uvc_device_handle_t* );
uvc_get_format_descs = _libuvc.uvc_get_format_descs
uvc_get_format_descs.argtypes = [c_void_p]
uvc_get_format_descs.restype = uvc_format_desc_p

# uvc_error_t uvc_probe_stream_ctrl(uvc_device_handle_t *devh, uvc_stream_ctrl_t *ctrl);
uvc_probe_stream_ctrl = _libuvc.uvc_probe_stream_ctrl
uvc_probe_stream_ctrl.argtypes = [c_void_p, POINTER(uvc_stream_ctrl)]

# uvc_error_t uvc_start_streaming(uvc_device_handle_t *devh,
#                                 uvc_stream_ctrl_t *ctrl,
#                                 uvc_frame_callback_t *cb,
#                                 void *user_ptr,
#                                 uint8_t flags);
uvc_start_streaming = _libuvc.uvc_start_streaming
uvc_start_streaming.argtypes = [
    c_void_p,
    POINTER(uvc_stream_ctrl),
    uvc_frame_callback,
    c_void_p,
    c_uint8
]

# DEPRICATED:
# uvc_error_t uvc_start_iso_streaming(uvc_device_handle_t *devh,
#                                     uvc_stream_ctrl_t *ctrl,
#                                     uvc_frame_callback_t *cb,
#                                     void *user_ptr);

# void uvc_stop_streaming(uvc_device_handle_t *devh);
uvc_stop_streaming = _libuvc.uvc_stop_streaming
uvc_stop_streaming.argtypes = [c_void_p]
uvc_stop_streaming.restype = None

# uvc_error_t uvc_stream_open_ctrl(uvc_device_handle_t *devh,
#                                  uvc_stream_handle_t **strmh,
#                                  uvc_stream_ctrl_t *ctrl);
uvc_stream_open_ctrl = _libuvc.uvc_stream_open_ctrl
uvc_stream_open_ctrl.argtypes = [
    c_void_p,
    POINTER(c_void_p),
    POINTER(uvc_stream_ctrl)
]

# uvc_error_t uvc_stream_ctrl(uvc_stream_handle_t *strmh, uvc_stream_ctrl_t *ctrl);
uvc_stream_ctrl_f = _libuvc.uvc_stream_ctrl
uvc_stream_ctrl_f.argtypes = [c_void_p, POINTER(uvc_stream_ctrl)]

# uvc_error_t uvc_stream_start(uvc_stream_handle_t *strmh,
#                              uvc_frame_callback_t *cb,
#                              void *user_ptr,
#                              uint8_t flags);
uvc_stream_start = _libuvc.uvc_stream_start
uvc_stream_start.argtypes = [
    c_void_p,
    uvc_frame_callback,
    c_void_p,
    c_uint8
]

# DEPRICATED:
# uvc_error_t uvc_stream_start_iso(uvc_stream_handle_t *strmh,
#                                  uvc_frame_callback_t *cb,
#                                  void *user_ptr);

# uvc_error_t uvc_stream_get_frame(uvc_stream_handle_t *strmh,
#                                  uvc_frame_t **frame,
#                                  int32_t timeout_us);
uvc_stream_get_frame = _libuvc.uvc_stream_get_frame
uvc_stream_get_frame.argtypes = [
    c_void_p,
    POINTER(POINTER(uvc_frame)),
    c_int32
]

# uvc_error_t uvc_stream_stop(uvc_stream_handle_t *strmh);
uvc_stream_stop = _libuvc.uvc_stream_stop
uvc_stream_stop.argtypes = [c_void_p]

# void uvc_stream_close(uvc_stream_handle_t *strmh);
uvc_stream_close = _libuvc.uvc_stream_close
uvc_stream_close.argtypes = [c_void_p]
uvc_stream_close.restype = None

# int uvc_get_ctrl_len(uvc_device_handle_t *devh, uint8_t unit, uint8_t ctrl);
uvc_get_ctrl_len = _libuvc.uvc_get_ctrl_len
uvc_get_ctrl_len.argtypes = [c_void_p, c_uint8, c_uint]

# int uvc_get_ctrl(uvc_device_handle_t *devh,
#                  uint8_t unit,
#                  uint8_t ctrl,
#                  void *data,
#                  int len,
#                  enum uvc_req_code req_code);
uvc_get_ctrl = _libuvc.uvc_get_ctrl
uvc_get_ctrl.argtypes = [
    c_void_p,
    c_uint8,
    c_uint8,
    c_void_p,
    c_int,
    c_int
]

# int uvc_set_ctrl(uvc_device_handle_t *devh,
#                  uint8_t unit,
#                  uint8_t ctrl,
#                  void *data,
#                  int len);
uvc_set_ctrl = _libuvc.uvc_set_ctrl
uvc_set_ctrl.argtypes = [
    c_void_p,
    c_uint8,
    c_uint8,
    c_void_p,
    c_int
]

# uvc_error_t uvc_get_power_mode(uvc_device_handle_t *devh,
#                                enum uvc_device_power_mode *mode,
#                                enum uvc_req_code req_code);
uvc_get_power_mode = _libuvc.uvc_get_power_mode
uvc_get_power_mode.argtypes = [
    c_void_p,
    POINTER(c_int),
    c_int
]

# uvc_error_t uvc_set_power_mode(uvc_device_handle_t *devh,
#                                enum uvc_device_power_mode mode);
uvc_set_power_mode = _libuvc.uvc_set_power_mode
uvc_set_power_mode.argtypes = [c_void_p, c_int]

# const char* uvc_strerror(uvc_error_t err);
uvc_strerror = _libuvc.uvc_strerror
uvc_strerror.argtypes = [c_int]
uvc_strerror.restype = c_char_p

# TODO: Implement control accessors

# Not Implemented:
# Functions that print to stdout/stderr:
# uvc_perror, uvc_print_diag, uvc_print_stream_ctrl
#
# Functions that do color conversion, decompression or allocate frames.
# Use pyuvc if you need this functionality, as it is written in
# cython for speed
