package vswitch;

import vfd.IP;
import vproxybase.util.exception.AlreadyExistException;
import vproxybase.util.exception.XException;
import vproxybase.selector.SelectorEventLoop;
import vproxybase.util.Network;
import vfd.MacAddress;

public class Table {
    public final int vni;
    public final Network v4network;
    public final Network v6network;
    public final MacTable macTable;
    public final ArpTable arpTable;
    public final SyntheticIpHolder ips;
    public final RouteTable routeTable;

    public Table(int vni, SelectorEventLoop loop,
                 Network v4network, Network v6network,
                 int macTableTimeout, int arpTableTimeout) {
        this.vni = vni;
        this.v4network = v4network;
        this.v6network = v6network;

        macTable = new MacTable(loop, macTableTimeout);
        arpTable = new ArpTable(loop, arpTableTimeout);
        ips = new SyntheticIpHolder(this);
        routeTable = new RouteTable(this);
    }

    public void setMacTableTimeout(int macTableTimeout) {
        macTable.setTimeout(macTableTimeout);
    }

    public void setArpTableTimeout(int arpTableTimeout) {
        arpTable.setTimeout(arpTableTimeout);
    }

    public void addIp(IP ip, MacAddress mac) throws AlreadyExistException, XException {
        ips.add(ip, mac);
    }

    public void clearCache() {
        macTable.clearCache();
        arpTable.clearCache();
    }

    public void setLoop(SelectorEventLoop loop) {
        macTable.setLoop(loop);
        arpTable.setLoop(loop);
    }

    public MacAddress lookup(IP ip) {
        var mac = arpTable.lookup(ip);
        if (mac == null) {
            mac = ips.lookup(ip);
        }
        return mac;
    }

    public boolean macReachable(MacAddress mac) {
        return macTable.lookup(mac) != null || ips.lookupByMac(mac) != null;
    }

    @Override
    public String toString() {
        return "Table{" +
            "vni=" + vni +
            ", v4network=" + v4network +
            ", v6network=" + v6network +
            ", macTable=" + macTable +
            ", arpTable=" + arpTable +
            ", ips=" + ips +
            ", routeTable=" + routeTable +
            '}';
    }
}