package vfd.posix;

import vfd.SocketFD;

import java.io.IOException;

public class PosixSocketFD extends PosixNetworkFD implements SocketFD {
    public PosixSocketFD(Posix posix) {
        super(posix);
    }

    public PosixSocketFD(Posix posix, int fd, boolean ipv4) {
        this(posix);
        this.fd = fd;
        this.ipv4 = ipv4;
        connected = true;
    }

    @Override
    public void shutdownOutput() throws IOException {
        checkFD();
        checkConnected();
        checkNotClosed();
        posix.shutdownOutput(fd);
    }

    @Override
    public boolean finishConnect() throws IOException {
        checkFD();
        checkNotClosed();
        connected = true;
        return true;
    }
}
