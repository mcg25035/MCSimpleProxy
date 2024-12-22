package com.mcloudtw;

import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

/**
 * 集中管理 DNS 解析的設定
 */
public class DNSResolverConfig {

    private final DnsAddressResolverGroup resolverGroup;

    public DNSResolverConfig() {
        // 設置 DNS Resolver，縮短 TTL
        DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder()
                .channelType(NioDatagramChannel.class)
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
                // 設定 minTtl, maxTtl, negativeTtl 為 1 秒
                .ttl(1, 1)
                .negativeTtl(1);

        this.resolverGroup = new DnsAddressResolverGroup(resolverBuilder);
    }

    public DnsAddressResolverGroup getResolverGroup() {
        return resolverGroup;
    }
}
