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
CMD_NONE = b'\x00\x00'
CMD_CONNECT_SOCKET = b'\x1F\xA5'
CMD_CLOSE_ACCESSORY = b'\x00\x0A'


# TODO: currently unused constant for linux, needed to listen for usb connected events
NETLINK_KOBJECT_UEVENT = 15
