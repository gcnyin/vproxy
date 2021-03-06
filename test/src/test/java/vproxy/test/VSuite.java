package vproxy.test;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import vproxy.test.cases.*;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    BeforeAll.class,

    TestTcpLB.class,
    TestIpParser.class,
    TestNetMask.class,
    TestTimer.class,
    TestResolver.class,
    TestSocks5.class,
    TestConnectClient.class,
    TestSSL.class,
    TestProtocols.class,
    TestHttp1Parser.class,
    TestHttp2Decoder.class,
    TestHealthCheck.class,
    TestPacket.class,
    TestRouteTable.class,
    TestTCP.class,
    TestHttpServer.class,
    TestNetServerClient.class,
    TestPrometheus.class,
    TestPromise.class,
    TestUtilities.class,
    TestByteArrayBuilder.class,

    AfterAll.class
})
public class VSuite {
}
