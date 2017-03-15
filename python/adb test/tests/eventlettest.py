import eventlet
from eventlet import green


def blah():
    while 1:
        print("Yo!")
        green.time.sleep(1)


def run(pipe):
    #pipe.settimeout(5)
    eventlet.spawn_n(blah)

    i = 0
    while 1:
        r, w, x = green.select.select([pipe.fileno()], [], [])
        if r:
            resp = pipe.recv()
            print("Child Received: %d" % resp)
            i += 1


