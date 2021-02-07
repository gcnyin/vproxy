package vproxybase.processor.http1;

import vfd.IPPort;
import vproxybase.processor.Hint;
import vproxybase.processor.OOContext;
import vproxybase.processor.Processor;
import vproxybase.util.ByteArray;
import vproxybase.util.LogType;
import vproxybase.util.Logger;

public class HttpContext extends OOContext<HttpSubContext> {
    final String clientAddress;
    final String clientPort;

    int currentBackend = -1;
    boolean upgradedConnection = false;

    HttpSubContext frontendSubContext;
    boolean frontendExpectingResponse = false;
    int frontendExpectingResponseFrom = -1; // backend connId

    public HttpContext(IPPort clientSock) {
        clientAddress = clientSock == null ? null : clientSock.getAddress().formatToIPString();
        clientPort = clientSock == null ? null : "" + clientSock.getPort();
    }

    @Override
    public int connection(HttpSubContext front) {
        int returnConnId;
        if (front.isIdle()) {
            // the state may turn to idle after calling feed()
            // the connection() will be called after calling feed()
            // so here we should return the last recorded backend id
            // then set the id to -1
            int foo = currentBackend;
            currentBackend = -1;
            returnConnId = foo;
        } else {
            returnConnId = currentBackend;
        }

        if (frontendExpectingResponse && returnConnId != -1) {
            // the data is proxied to the specified backend
            // so response is expected to be from that backend
            frontendExpectingResponseFrom = returnConnId;
        }

        return returnConnId;
    }

    @Override
    public Hint connectionHint(HttpSubContext front) {
        String uri = front.theUri;
        String host = front.theHostHeader;

        if (host == null && uri == null) {
            return null;
        } else if (host == null) {
            // assert uri != null;
            return Hint.ofUri(uri);
        } else if (uri == null) {
            // assert host != null;
            return Hint.ofHost(host);
        } else {
            // assert host != null && uri != null;
            return Hint.ofHostUri(host, uri);
        }
    }

    @Override
    public void chosen(HttpSubContext front, HttpSubContext subCtx) {
        currentBackend = subCtx.connId;
        // backend chosen, so response is expected to be from that backend
        frontendExpectingResponseFrom = currentBackend;
    }

    void clearFrontendExpectingResponse(Processor.SubContext subCtx) {
        if (!frontendExpectingResponse) {
            Logger.error(LogType.IMPROPER_USE, "frontend expecting response is already false");
            return;
        }
        if (frontendExpectingResponseFrom != subCtx.connId) {
            Logger.error(LogType.IMPROPER_USE, "the expected response is from " + frontendExpectingResponseFrom + ", not " + subCtx.connId);
            return;
        }
        frontendExpectingResponseFrom = -1;
        frontendExpectingResponse = false;

        frontendSubContext.delegate.resume();
        if (frontendSubContext.storedBytesForProcessing != null) {
            try {
                frontendSubContext.feed(ByteArray.allocate(0));
            } catch (Exception e) {
                assert Logger.lowLevelDebug("feed return error: " + e);
            }
        }
    }
}
