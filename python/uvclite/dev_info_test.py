import uvclite

if __name__ == '__main__':
    with uvclite.UVCContext() as context:
        devlist = context.get_device_list()
        #dev = context.find_device()
        print(len(devlist))

        for dev in devlist:
            devdesc = dev.get_device_descriptor()
            print("Vendor ID: %d" % devdesc.idVendor)
            print("Product ID: %d" % devdesc.idProduct)
            print("UVC Standard: %d" % devdesc.bcdUVC)
            print("Serial Number: %s" % devdesc.serialNumber)
            print("Manufacturer: %s" % devdesc.manufacturer)
            print("Product Name %s" % devdesc.product)
            dev.free_device_descriptor()
            print("Freed descriptor")

        devlist = context.get_device_list()
        #dev = context.find_device()
        print(len(devlist))

        for dev in devlist:
            dev.open()
            dev.print_diagnostics()
            dev.close()

        