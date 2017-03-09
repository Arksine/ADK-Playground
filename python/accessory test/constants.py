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
CMD_TEST = b'\x00\x01'           # signals that the payload received is a short
CMD_CAM_FRAME = b'\x00\x02'      # signals that the payload sent is a camera frame
CMD_CAM_START = b'\x00\x03'
CMD_CAM_STOP = b'\x00\x04'
CMD_APP_CONNECTED = b'\xFF\xFD'  # signal from app that it is connected
CMD_TERMINATE = b'\xFF\xFE'      # signal to terminate both the app and this process
CMD_EXIT = b'\xFF\xFF'           # signal the accessory app to close



# TODO: currently unused constant for linux, needed to listen for usb connected events
NETLINK_KOBJECT_UEVENT = 15
