module vproxy.base {
    requires jdk.unsupported;

    exports vjson;
    exports vjson.listener;
    exports vjson.util;
    exports vjson.ex;
    exports vjson.cs;
    exports vjson.parser;
    exports vjson.simple;
    exports vjson.stringifier;
    exports vproxybase;
    exports vproxybase.selector;
    exports vproxybase.selector.wrap;
    exports vproxybase.selector.wrap.streamed;
    exports vproxybase.selector.wrap.blocking;
    exports vproxybase.selector.wrap.udp;
    exports vproxybase.selector.wrap.h2streamed;
    exports vproxybase.selector.wrap.arqudp;
    exports vproxybase.selector.wrap.kcp;
    exports vproxybase.selector.wrap.kcp.mock;
    exports vproxybase.socks;
    exports vproxybase.connection;
    exports vproxybase.connection.util;
    exports vproxybase.util;
    exports vproxybase.util.ringbuffer;
    exports vproxybase.util.ringbuffer.ssl;
    exports vproxybase.util.crypto;
    exports vproxybase.util.direct;
    exports vproxybase.util.ex;
    exports vproxybase.util.io;
    exports vproxybase.util.bytearray;
    exports vproxybase.util.nio;
    exports vproxybase.util.exception;
    exports vproxybase.processor;
    exports vproxybase.processor.http1;
    exports vproxybase.processor.http1.entity;
    exports vproxybase.processor.http1.builder;
    exports vproxybase.processor.http2;
    exports vproxybase.processor.common;
    exports vproxybase.processor.http;
    exports vproxybase.processor.dubbo;
    exports vproxybase.prometheus;
    exports vproxybase.component.elgroup;
    exports vproxybase.component.svrgroup;
    exports vproxybase.component.check;
    exports vproxybase.protocol;
    exports vproxybase.redis;
    exports vproxybase.redis.entity;
    exports vproxybase.redis.application;
    exports vproxybase.http;
    exports vproxybase.http.connect;
    exports vproxybase.dhcp;
    exports vproxybase.dhcp.options;
    exports vproxybase.dns;
    exports vproxybase.dns.rdata;
    exports tlschannel.impl;
    exports vfd;
    exports vfd.posix;
    exports vfd.jdk;
    exports vfd.abs;
    exports vfd.windows;
    exports vpacket;
    exports vmirror;
    exports com.twitter.hpack;
    exports vpacket.conntrack.tcp;
    exports vpacket.conntrack;

    uses vfd.FDs;
    uses vproxybase.processor.ProcessorRegistry;
}
