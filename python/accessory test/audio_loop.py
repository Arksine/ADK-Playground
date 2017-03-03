#!/usr/bin/python3
# coding=utf-8
"""audio_loop.py

"""

import threading
import queue
import signal
import alsaaudio

# TODO: Tested and works!  Much better than alsaloop too. Handles phone disconnections well. 
# May want  volume control for the output device

class AlsaLoop(object):
    """
    TODO: Docstring
    """

    def __init__(self, in_card_index, out_card_index):
        self._is_running = False
        self._is_writing = False
        self._in_card = alsaaudio.PCM(type=alsaaudio.PCM_CAPTURE, cardindex=in_card_index)
        self._out_card = alsaaudio.PCM(cardindex=out_card_index)
        self._audio_buffer = queue.Queue()

        self._in_card.setchannels(2)
        self._in_card.setrate(44100)
        self._in_card.setformat(alsaaudio.PCM_FORMAT_S16_LE)
        self._in_card.setperiodsize(200)

        self._out_card.setchannels(2)
        self._out_card.setrate(44100)
        self._out_card.setformat(alsaaudio.PCM_FORMAT_S16_LE)
        self._out_card.setperiodsize(200)

    def _write(self):
        while not self._audio_buffer.empty():
            data = self._audio_buffer.get()
            self._out_card.write(data)
        self._is_writing = False

    def run(self):
        """
        Main reading loop.
        """
        self._is_running = True
        while self._is_running:
            try:
                data = self._in_card.read()
            except alsaaudio.ALSAAudioError as err:
                if 'Input/output error' in err.args[0]:
                    print('Read Timeout')
                    continue
                else:
                    raise err

            self._audio_buffer.put(data[1])

            # I could use a thread attribute and check if the thread is alive instead
            # of using the isWriting attribute.  12 periods stored is a little over 50ms of buffer
            if not self._is_writing and self._audio_buffer.qsize() >= 12:
                self._is_writing = True
                write_thread = threading.Thread(target=self._write)
                write_thread.start()

    def stop(self):
        """
        Stops the reading loop
        """
        self._is_running = False


SHUTDOWN = False

def main():
    """
    TODO: Docstring
    """

    loop = AlsaLoop(2, 1)

    def signal_handler(sig, stack):
        """
        Handles exit events.
        """
        global SHUTDOWN
        SHUTDOWN = True
        loop.stop()
        print('Exiting...')

    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, signal_handler)


    while not SHUTDOWN:
        try:
            loop.run()
        except alsaaudio.ALSAAudioError as err:
            print(err.args)
            break

if __name__ == '__main__':
    main()
