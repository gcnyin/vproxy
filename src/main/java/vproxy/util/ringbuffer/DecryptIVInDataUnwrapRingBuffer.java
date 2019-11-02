package vproxy.util.ringbuffer;

import vproxy.util.Logger;
import vproxy.util.RingBuffer;
import vproxy.util.crypto.BlockCipherKey;
import vproxy.util.crypto.StreamingCFBCipher;
import vproxy.util.crypto.Utils;

import java.nio.ByteBuffer;

public class DecryptIVInDataUnwrapRingBuffer extends AbstractUnwrapByteBufferRingBuffer implements RingBuffer {
    private final BlockCipherKey key;

    private int requiredIvLen;
    private byte[] iv;
    private StreamingCFBCipher cipher;

    public DecryptIVInDataUnwrapRingBuffer(ByteBufferRingBuffer plainBufferForApp, BlockCipherKey key) {
        super(plainBufferForApp);
        this.key = key;
        this.requiredIvLen = key.ivLen();
        this.iv = new byte[requiredIvLen];
    }

    @Override
    protected void handleEncryptedBuffer(ByteBuffer buf, boolean[] underflow, boolean[] errored) {
        if (requiredIvLen != 0) {
            readIv(buf);
            if (requiredIvLen == 0) {
                buildCipher();
                readData(buf);
            }
        } else {
            readData(buf);
        }
    }

    private void readIv(ByteBuffer buf) {
        int len = requiredIvLen;
        int bufLen = buf.limit() - buf.position();
        if (len > bufLen) {
            len = bufLen;
        }
        buf.get(iv, iv.length - requiredIvLen, len);
        requiredIvLen -= len;
    }

    private void buildCipher() {
        cipher = new StreamingCFBCipher(key, false, iv);
    }

    private void readData(ByteBuffer input) {
        int len = input.limit() - input.position();
        if (len == 0) {
            return;
        }
        byte[] array = new byte[len];
        input.get(array);

        byte[] ret = cipher.update(array, 0, array.length);
        assert Logger.lowLevelDebug("decrypt " + ret.length + " bytes");
        assert Utils.printBytes(ret);
        recordIntermediateBuffer(ByteBuffer.wrap(ret));
    }
}
