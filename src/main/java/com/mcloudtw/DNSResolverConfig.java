package com.mcloudtw;

import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

public class DNSResolverConfig {

    private final DnsAddressResolverGroup resolverGroup;

    public DNSResolverConfig() {
        DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder()
                .channelType(NioDatagramChannel.class)
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
                .ttl(1, 1)
                .negativeTtl(1);

        this.resolverGroup = new DnsAddressResolverGroup(resolverBuilder);
    }

    public DnsAddressResolverGroup getResolverGroup() {
        return resolverGroup;
    }
}
