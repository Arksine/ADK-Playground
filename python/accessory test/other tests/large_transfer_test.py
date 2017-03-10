#!/usr/bin/python3
# coding=utf-8

import struct

def build_large_transfer():
    return bytes((i % 256) for i in range(31525))

if __name__ == '__main__':
    bytelist = build_large_transfer()
    print(len(bytelist))
