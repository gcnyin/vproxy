package vproxy.vswitch.stack;

import vproxy.base.util.ByteArray;
import vproxy.base.util.Consts;
import vproxy.base.util.Logger;
import vproxy.base.util.thread.VProxyThread;
import vproxy.vfd.IPPort;
import vproxy.vfd.IPv4;
import vproxy.vfd.IPv6;
import vproxy.vpacket.AbstractIpPacket;
import vproxy.vpacket.Ipv4Packet;
import vproxy.vpacket.Ipv6Packet;
import vproxy.vpacket.TcpPacket;
import vproxy.vpacket.conntrack.tcp.*;
import vproxy.vswitch.PacketBuffer;
import vproxy.vswitch.SwitchContext;
import vproxy.vswitch.Table;

import java.util.Collections;
import java.util.List;

public class L4 {
    private final L3 L3;
    private final SwitchContext swCtx;

    public L4(SwitchContext swCtx, L3 l3) {
        this.swCtx = swCtx;
        L3 = l3;
    }

    public boolean input(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("L4.input(" + pkb + ")");
        if (!wantToHandle(pkb)) {
            assert Logger.lowLevelDebug("L4 stack doesn't handle this packet");
            return false;
        }
        if (pkb.needTcpReset) {
            assert Logger.lowLevelDebug("reset the packet");
            sendRst(pkb);
            return true;
        }
        if (pkb.tcp != null) {
            handleTcp(pkb);
            return true;
        }
        // implement more L4 protocols in the future
        assert Logger.lowLevelDebug("this packet is not handled by L4");
        return true;
    }

    private boolean wantToHandle(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("wantToHandle(" + pkb + ")");

        // implement more L4 protocols in the future
        boolean result = false;

        var ipPkt = pkb.ipPkt;
        if (ipPkt.getPacket() instanceof TcpPacket) {
            var tcpPkt = (TcpPacket) ipPkt.getPacket();
            IPPort src = new IPPort(ipPkt.getSrc(), tcpPkt.getSrcPort());
            IPPort dst = new IPPort(ipPkt.getDst(), tcpPkt.getDstPort());
            var tcpEntry = pkb.table.conntrack.lookup(src, dst);
            if (tcpEntry != null) {
                pkb.tcp = tcpEntry;
                result = true;
            } else if (tcpPkt.getFlags() == Consts.TCP_FLAGS_SYN) {
                // only consider the packets with only SYN on it
                var listenEntry = pkb.table.conntrack.lookupListen(dst);
                if (listenEntry != null) {
                    assert Logger.lowLevelDebug("got new connection");

                    // check backlog
                    if (listenEntry.synBacklog.size() >= ListenEntry.MAX_SYN_BACKLOG_SIZE) {
                        assert Logger.lowLevelDebug("syn-backlog is full");
                        // here we reset the connection instead of dropping it like linux
                        pkb.needTcpReset = true;
                    } else {
                        tcpEntry = pkb.table.conntrack.create(listenEntry, src, dst, tcpPkt.getSeqNum());
                        listenEntry.synBacklog.add(tcpEntry);
                        pkb.tcp = tcpEntry;
                    }
                    result = true;
                }
            } else {
                assert Logger.lowLevelDebug("need to reset");
                pkb.needTcpReset = true;
                result = true;
            }
        }

        assert Logger.lowLevelDebug("wantToHandle(" + pkb + ") = " + result);
        return result;
    }

    private void handleTcp(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcp(" + pkb + ")");
        var tcp = pkb.tcp;
        switch (tcp.getState()) {
            case CLOSED:
                handleTcpClosed(pkb);
                break;
            case SYN_SENT:
                handleTcpSynSent(pkb);
                break;
            case SYN_RECEIVED:
                handleTcpSynReceived(pkb);
                break;
            case ESTABLISHED:
                handleTcpEstablished(pkb);
                break;
            case FIN_WAIT_1:
                handleTcpFinWait1(pkb);
                break;
            case FIN_WAIT_2:
                handleTcpFinWait2(pkb);
                break;
            case CLOSE_WAIT:
                handleTcpCloseWait(pkb);
                break;
            case CLOSING:
                handleTcpClosing(pkb);
                break;
            case LAST_ACK:
                handleLastAck(pkb);
                break;
            case TIME_WAIT:
                handleTimeWait(pkb);
                break;
            default:
                Logger.shouldNotHappen("should not reach here");
        }
    }

    private TcpPacket buildSynAck(PacketBuffer pkb) {
        TcpPacket respondTcp = TcpUtils.buildCommonTcpResponse(pkb.tcp);
        respondTcp.setFlags(Consts.TCP_FLAGS_SYN | Consts.TCP_FLAGS_ACK);
        respondTcp.setWindow(65535);
        {
            var optMss = new TcpPacket.TcpOption();
            optMss.setKind(Consts.TCP_OPTION_MSS);
            optMss.setData(ByteArray.allocate(2).int16(0, TcpEntry.RCV_MSS));
            respondTcp.getOptions().add(optMss);
        }
        {
            int scale = pkb.tcp.receivingQueue.getWindowScale();
            int cnt = 0;
            while (scale != 1) {
                scale /= 2;
                cnt += 1;
            }
            if (cnt != 0) {
                var optWindowScale = new TcpPacket.TcpOption();
                optWindowScale.setKind(Consts.TCP_OPTION_WINDOW_SCALE);
                optWindowScale.setData(ByteArray.allocate(1).set(0, (byte) cnt));
                respondTcp.getOptions().add(optWindowScale);
            }
        }
        return respondTcp;
    }

    private void sendRst(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("sendRst(" + pkb + ")");

        // maybe there's no tcp entry when sending rst
        // so we can only use the fields in the received packet

        var inputTcpPkt = pkb.tcpPkt;

        TcpPacket respondTcp = new TcpPacket();
        respondTcp.setSrcPort(inputTcpPkt.getDstPort());
        respondTcp.setDstPort(inputTcpPkt.getSrcPort());
        respondTcp.setSeqNum(inputTcpPkt.getAckNum());
        respondTcp.setAckNum(inputTcpPkt.getSeqNum());
        respondTcp.setFlags(Consts.TCP_FLAGS_RST);
        respondTcp.setWindow(0);

        AbstractIpPacket ipPkt;
        if (pkb.ipPkt.getSrc() instanceof IPv4) {
            var ipv4 = new Ipv4Packet();
            ipv4.setSrc((IPv4) pkb.ipPkt.getDst());
            ipv4.setDst((IPv4) pkb.ipPkt.getSrc());
            var tcpBytes = respondTcp.buildIPv4TcpPacket(ipv4);

            ipv4.setVersion(4);
            ipv4.setIhl(5);
            ipv4.setTotalLength(20 + tcpBytes.length());
            ipv4.setTtl(64);
            ipv4.setProtocol(Consts.IP_PROTOCOL_TCP);
            ipv4.setOptions(ByteArray.allocate(0));

            ipv4.setPacket(respondTcp);
            ipPkt = ipv4;
        } else {
            var ipv6 = new Ipv6Packet();
            ipv6.setSrc((IPv6) pkb.ipPkt.getDst());
            ipv6.setDst((IPv6) pkb.ipPkt.getSrc());
            var tcpBytes = respondTcp.buildIPv6TcpPacket(ipv6);

            ipv6.setVersion(6);
            ipv6.setNextHeader(Consts.IP_PROTOCOL_TCP);
            ipv6.setPayloadLength(tcpBytes.length());
            ipv6.setHopLimit(64);
            ipv6.setExtHeaders(Collections.emptyList());

            ipv6.setPacket(respondTcp);
            ipPkt = ipv6;
        }

        pkb.replacePacket(ipPkt);
        L3.output(pkb);
    }

    private void handleTcpClosed(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpClosed");

        var tcpPkt = pkb.tcpPkt;
        // only handle syn
        if (tcpPkt.getFlags() != Consts.TCP_FLAGS_SYN) {
            assert Logger.lowLevelDebug("not SYN packet");
            return;
        }
        pkb.tcp.setState(TcpState.SYN_RECEIVED);
        // get tcp options from the syn
        int mss = TcpEntry.SND_DEFAULT_MSS;
        int windowScale = 1;
        for (var opt : tcpPkt.getOptions()) {
            switch (opt.getKind()) {
                case Consts.TCP_OPTION_MSS:
                    mss = opt.getData().uint16(0);
                    break;
                case Consts.TCP_OPTION_WINDOW_SCALE:
                    int s = opt.getData().uint8(0);
                    windowScale = 1 << s;
                    break;
            }
        }
        pkb.tcp.sendingQueue.init(tcpPkt.getWindow(), mss, windowScale);

        // SYN-ACK
        TcpPacket respondTcp = buildSynAck(pkb);
        AbstractIpPacket respondIp = TcpUtils.buildIpResponse(pkb.tcp, respondTcp);

        pkb.tcp.sendingQueue.incAllSeq();

        pkb.replacePacket(respondIp);
        L3.output(pkb);
    }

    private void handleTcpSynSent(@SuppressWarnings("unused") PacketBuffer pkb) {
        Logger.shouldNotHappen("unsupported yet: syn-sent state");
    }

    private void handleTcpSynReceived(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpSynReceived");
        // first check whether the packet has ack, and if so, check the ack number
        var tcpPkt = pkb.tcpPkt;
        if (tcpPkt.isSyn()) {
            assert Logger.lowLevelDebug("probably a syn retransmission");
            if (tcpPkt.getSeqNum() == pkb.tcp.receivingQueue.getAckedSeq() - 1) {
                assert Logger.lowLevelDebug("seq matches");
                pkb.tcp.sendingQueue.decAllSeq();
                TcpPacket respondTcp = buildSynAck(pkb);
                AbstractIpPacket respondIp = TcpUtils.buildIpResponse(pkb.tcp, respondTcp);
                pkb.tcp.sendingQueue.incAllSeq();
                pkb.replacePacket(respondIp);
                L3.output(pkb);
                return;
            }
        }
        if (!tcpPkt.isAck()) {
            assert Logger.lowLevelDebug("no ack flag set");
            return;
        }
        if (tcpPkt.getAckNum() != pkb.tcp.sendingQueue.getAckSeq()) {
            assert Logger.lowLevelDebug("wrong ack number");
            return;
        }
        connectionEstablishes(pkb);

        // then run the same handling as established
        handleTcpEstablished(pkb);
    }

    private void connectionEstablishes(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("connectionEstablishes");
        pkb.tcp.setState(TcpState.ESTABLISHED);
        // alert that this connection can be retrieved
        var parent = pkb.tcp.getParent();
        parent.synBacklog.remove(pkb.tcp);
        parent.backlog.add(pkb.tcp);
        parent.listenHandler.readable(parent);
    }

    private boolean handleTcpGeneralReturnFalse(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpGeneral");

        var tcpPkt = pkb.tcpPkt;

        // check whether seq matches
        var seq = tcpPkt.getSeqNum();
        var expect = pkb.tcp.receivingQueue.getExpectingSeq();
        var acked = pkb.tcp.receivingQueue.getAckedSeq();
        if (tcpPkt.isFin()) {
            if (seq != acked) {
                assert Logger.lowLevelDebug("data not fully consumed yet but received FIN");
                return true;
            }
        } else if (seq != expect) {
            if (!tcpPkt.isPsh() || seq > expect) {
                assert Logger.lowLevelDebug("invalid sequence number");
                return true;
            }
        }

        if (tcpPkt.isAck()) {
            long ack = tcpPkt.getAckNum();
            int window = tcpPkt.getWindow();
            pkb.tcp.sendingQueue.ack(ack, window);
            // then check whether there's data to send
            // because the window may forbid it from sending
            // ack resets the window so it might get chance to send data
            if (pkb.tcp.retransmissionTimer == null) {
                tcpStartRetransmission(pkb.table, pkb.tcp);
            }
        }
        return false;
    }

    private void handleTcpEstablished(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpEstablished");
        if (handleTcpGeneralReturnFalse(pkb)) {
            return;
        }
        var tcpPkt = pkb.tcpPkt;
        if (tcpPkt.isPsh()) {
            long seq = tcpPkt.getSeqNum();
            ByteArray data = tcpPkt.getData();
            pkb.tcp.receivingQueue.store(new Segment(seq, data));
        }
        if (tcpPkt.isFin()) {
            pkb.tcp.setState(TcpState.CLOSE_WAIT);
            pkb.tcp.receivingQueue.incExpectingSeq();
            tcpAck(pkb.table, pkb.tcp);
        }
    }

    private void handleTcpFinWait1(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpFinWait1");
        if (handleTcpGeneralReturnFalse(pkb)) {
            return;
        }
        var tcpPkt = pkb.tcpPkt;
        if (tcpPkt.isFin()) {
            if (pkb.tcp.sendingQueue.ackOfFinReceived()) {
                assert Logger.lowLevelDebug("transform to CLOSING");
                pkb.tcp.setState(TcpState.CLOSING);
                sendRst(pkb);
            } else {
                assert Logger.lowLevelDebug("received FIN but the previous sent FIN not acked");
            }
        } else {
            if (pkb.tcp.sendingQueue.ackOfFinReceived()) {
                assert Logger.lowLevelDebug("the sent FIN is acked, transform to FIN_WAIT_2");
                pkb.tcp.setState(TcpState.FIN_WAIT_2);
            }
        }
    }

    private void handleTcpFinWait2(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpFinWait2");
        if (handleTcpGeneralReturnFalse(pkb)) {
            return;
        }
        var tcpPkt = pkb.tcpPkt;
        if (tcpPkt.isFin()) {
            assert Logger.lowLevelDebug("transform to CLOSING");
            pkb.tcp.setState(TcpState.CLOSING);
            sendRst(pkb);
        }
    }

    private void handleTcpCloseWait(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpCloseWait");
        if (handleTcpGeneralReturnFalse(pkb)) {
            return;
        }
        var tcpPkt = pkb.tcpPkt;
        if (tcpPkt.isFin()) {
            assert Logger.lowLevelDebug("received FIN again, maybe it's retransmission");
            if (tcpPkt.getSeqNum() == pkb.tcp.receivingQueue.getExpectingSeq() - 1) {
                tcpAck(pkb.table, pkb.tcp);
            }
        }
    }

    private void handleTcpClosing(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("handleTcpClosing");
        if (handleTcpGeneralReturnFalse(pkb)) {
            return;
        }
        assert Logger.lowLevelDebug("drop any packet when it's in CLOSING state");
    }

    private void handleLastAck(@SuppressWarnings("unused") PacketBuffer pkb) {
        Logger.shouldNotHappen("unsupported yet: last-ack state");
    }

    private void handleTimeWait(@SuppressWarnings("unused") PacketBuffer pkb) {
        Logger.shouldNotHappen("unsupported yet: time-wait state");
    }

    public void output(PacketBuffer pkb) {
        assert Logger.lowLevelDebug("L4.output(" + pkb + ")");
        L3.output(pkb);
    }

    public void tcpAck(Table table, TcpEntry tcp) {
        assert Logger.lowLevelDebug("tcpAck(" + ", " + table + ", " + tcp + ")");

        if (tcp.receivingQueue.getWindow() == 0) {
            assert Logger.lowLevelDebug("no window, very bad, need to ack immediately");
            if (tcp.delayedAckTimer != null) {
                assert Logger.lowLevelDebug("cancel the timer");
                tcp.delayedAckTimer.cancel();
                tcp.delayedAckTimer = null;
            }
            sendAck(table, tcp);
            return;
        }
        if (tcp.delayedAckTimer != null) {
            assert Logger.lowLevelDebug("delayed ack already scheduled");
            return;
        }
        tcp.delayedAckTimer = swCtx.getSelectorEventLoop().delay(TcpEntry.DELAYED_ACK_TIMEOUT, () -> sendAck(table, tcp));
    }

    private void sendAck(Table table, TcpEntry tcp) {
        VProxyThread.current().newUuidDebugInfo();
        assert Logger.lowLevelDebug("sendAck(" + ", " + table + ", " + tcp + ")");

        if (tcp.delayedAckTimer != null) {
            tcp.delayedAckTimer.cancel();
            tcp.delayedAckTimer = null;
        }

        TcpPacket respondTcp = TcpUtils.buildAckResponse(tcp);
        AbstractIpPacket respondIp = TcpUtils.buildIpResponse(tcp, respondTcp);

        PacketBuffer pkb = PacketBuffer.fromPacket(table, respondIp);
        L3.output(pkb);
    }

    public void tcpStartRetransmission(Table table, TcpEntry tcp) {
        assert Logger.lowLevelDebug("tcpStartRetransmission(" + table + "," + tcp + ")");
        transmitTcp(table, tcp, 0, 0);
    }

    private void transmitTcp(Table table, TcpEntry tcp, long lastBeginSeq, int retransmissionCount) {
        assert Logger.lowLevelDebug("transmitTcp(" + table + "," + tcp + ")");

        if (tcp.retransmissionTimer != null) { // reset timer
            tcp.retransmissionTimer.cancel();
            tcp.retransmissionTimer = null;
        }

        // check whether need to reset the connection because of too many retransmits
        if (tcp.requireClosing() && retransmissionCount > TcpEntry.MAX_RETRANSMISSION_AFTER_CLOSING) {
            assert Logger.lowLevelDebug("conn " + tcp + " is closed due to too many retransmission after closing");
            resetTcpConnection(table, tcp);
            return;
        }

        List<Segment> segments = tcp.sendingQueue.fetch();
        if (segments.isEmpty()) { // no data to send, check FIN
            if (tcp.sendingQueue.needToSendFin()) {
                assert Logger.lowLevelDebug("need to send FIN");
                // fall through
            } else {
                // nothing to send
                assert Logger.lowLevelDebug("no need to retransmit after " + retransmissionCount + " time(s)");
                if (tcp.retransmissionTimer != null) {
                    tcp.retransmissionTimer.cancel();
                    tcp.retransmissionTimer = null;
                }
                afterTransmission(table, tcp);
                return;
            }
        }
        long currentBeginSeq = segments.isEmpty() ? tcp.sendingQueue.getFetchSeq() + 1 : segments.get(0).seqBeginInclusive;
        if (currentBeginSeq != lastBeginSeq) {
            assert Logger.lowLevelDebug("the sequence increased, it's not retransmitting after " + retransmissionCount + " time(s)");
            retransmissionCount = 0;
        }

        // initiate timer
        int delay = TcpEntry.RTO_MIN << retransmissionCount;
        if (delay <= 0 || delay > TcpEntry.RTO_MAX) { // overflow or exceeds maximum
            delay = TcpEntry.RTO_MAX;
        }
        assert Logger.lowLevelDebug("will delay " + delay + " ms then retransmit");
        final int finalRetransmissionCount = retransmissionCount;
        tcp.retransmissionTimer = swCtx.getSelectorEventLoop().delay(delay, () ->
            transmitTcp(table, tcp, currentBeginSeq, finalRetransmissionCount + 1)
        );

        if (segments.isEmpty()) {
            assert tcp.sendingQueue.needToSendFin();
            sendTcpFin(table, tcp);
        } else {
            for (var s : segments) {
                sendTcpPsh(table, tcp, s);
            }
        }
    }

    private void afterTransmission(Table table, TcpEntry tcp) {
        assert Logger.lowLevelDebug("afterTransmission(" + table + "," + tcp + "," + ")");

        if (tcp.requireClosing()) {
            assert Logger.lowLevelDebug("need to be closed");
            resetTcpConnection(table, tcp);
        }
    }

    public void resetTcpConnection(Table table, TcpEntry tcp) {
        VProxyThread.current().newUuidDebugInfo();
        assert Logger.lowLevelDebug("sendTcpRst(" + table + "," + tcp + "," + ")");

        PacketBuffer pkb = PacketBuffer.fromPacket(table, TcpUtils.buildIpResponse(tcp, TcpUtils.buildRstResponse(tcp)));
        output(pkb);

        tcp.setState(TcpState.CLOSED);
        table.conntrack.remove(tcp.source, tcp.destination);
    }

    private void sendTcpPsh(Table table, TcpEntry tcp, Segment s) {
        VProxyThread.current().newUuidDebugInfo();
        assert Logger.lowLevelDebug("sendTcpPsh(" + table + "," + tcp + "," + s + ")");

        TcpPacket tcpPkt = TcpUtils.buildCommonTcpResponse(tcp);
        tcpPkt.setSeqNum(s.seqBeginInclusive);
        tcpPkt.setFlags(Consts.TCP_FLAGS_PSH | Consts.TCP_FLAGS_ACK);
        tcpPkt.setData(s.data);
        AbstractIpPacket ipPkt = TcpUtils.buildIpResponse(tcp, tcpPkt);

        PacketBuffer pkb = PacketBuffer.fromPacket(table, ipPkt);
        L3.output(pkb);
    }

    private void sendTcpFin(Table table, TcpEntry tcp) {
        VProxyThread.current().newUuidDebugInfo();
        assert Logger.lowLevelDebug("sendTcpFin(" + table + "," + tcp + ")");

        TcpPacket tcpPkt = TcpUtils.buildCommonTcpResponse(tcp);
        tcpPkt.setSeqNum(tcp.sendingQueue.getFetchSeq());
        tcpPkt.setFlags(Consts.TCP_FLAGS_FIN | Consts.TCP_FLAGS_ACK);
        AbstractIpPacket ipPkt = TcpUtils.buildIpResponse(tcp, tcpPkt);

        PacketBuffer pkb = PacketBuffer.fromPacket(table, ipPkt);
        L3.output(pkb);
    }
}
