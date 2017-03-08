# USB Definitions
MANUFACTURER = "Arksine"
MODEL_NAME = "AccesoryTest"
DESCRIPTION = "Test Accessory comms with android"
VERSION = "0.1"
URI = "http://put.github.url.here"
SERIAL_NUMBER = "1337"

# TODO: need all known compatible android smartphone vendors, so I can attempt
# to force accessory mode
COMPATIBLE_VIDS = (0x18D1, 0x0FCE, 0x0E0F, 0x04E8)
ACCESSORY_VID = 0x18D1
ACCESSORY_PID = (0x2D00, 0x2D01, 0x2D04, 0x2D05)


# Command values to be sent to the accessory
NONE = b'\x00\x00'
TEST = b'\x00\x01'
CAM_FRAME = b'\x00\x02'
EXIT = b'\xFF\xFF'


# Commands received from the accessory
# TODO: currently these commands are simply received as values after
#       a header.  In the future we will want them to be part of the header
EXIT_APP_CMD = 0xFFFF   # Sends an exit command to the accessory, but this script keeps running
TERMINATE_CMD = 0xFFFE  # App and this process are terminated
APP_CONNECTED_CMD = 0xFFFD # application is connected
START_CAMERA_CMD = 100
STOP_CAMERA_CMD = 200

# TODO: currently unused constant for linux, needed to listen for usb connected events
NETLINK_KOBJECT_UEVENT = 15
