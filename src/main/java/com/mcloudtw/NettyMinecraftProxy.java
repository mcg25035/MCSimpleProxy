//
//      _ooOoo_
//     o8888888o
//     88" . "88
//     (| -_- |)
//      O\ = /O
//  ____/`---'\____
//.'  \\|     |//  `.
// /  \\|||  :  |||//  \
///  _||||| -:- |||||-  \
//|   | \\\  -  /// |   |
//| \_|  ''\---/''  |   |
//\  .-\__  `-`  ___/-. /
//___`. .'  /--.--\  `. . __
//."" '<  `.___\_<|>_/___.'  >'"".
//| | :  `- \`.;`\ _ /`;.`/ - ` : | |
//\  \ `-.   \_ __\ /__ _/   .-` /  /
//===`-.____`-.___\_____/___.-`____.-'===
//         `=---='
//^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//           佛祖保佑     永無BUG
//
//       \|/     {_}  {_}  {_}     \|/
package com.mcloudtw;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.security.Security;

public class NettyMinecraftProxy {
    private static final int LISTEN_PORT = 25565;
    private static final String REMOTE_HOST = "tw1.mcloudtw.com";
    private static final int REMOTE_PORT = 25565;

    public static void main(String[] args) throws InterruptedException {
        // Disable Java's DNS caching to ensure fresh DNS resolutions
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        System.out.println("Java DNS caching disabled.");

        // Initialize Netty event loop groups
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        System.out.println("Netty EventLoopGroups initialized.");

        // Configure Netty's DNS resolver with minimized caching
        DnsNameResolverBuilder resolverBuilder = new DnsNameResolverBuilder()
                .channelType(NioDatagramChannel.class)
                .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
                .ttl(1, 1) // Set minTtl and maxTtl to 1 second to minimize caching
                .negativeTtl(1); // Set negative TTL to 1 second

        DnsAddressResolverGroup resolverGroup = new DnsAddressResolverGroup(resolverBuilder);
        System.out.println("Netty DNS resolver configured with minimal caching.");

        try {
            // Set up the server bootstrap
            ServerBootstrap b = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("debugLogger", new LoggingHandler(LogLevel.DEBUG));
                            // 幫 MinecraftHandshakeCheckHandler 取名為 "handshakeHandler"
                            p.addLast("handshakeHandler", new MinecraftHandshakeCheckHandler(REMOTE_HOST, REMOTE_PORT, resolverGroup));
                            System.out.println("Initialized channel pipeline for new connection.");
                        }
                    });

            // Bind the server to the specified port
            ChannelFuture f = b.bind(LISTEN_PORT).sync();
            System.out.println("[11:45:14 INFO]: Done (114.514s)!");
            System.out.println("Proxy listening on port " + LISTEN_PORT);

            // Wait until the server socket is closed
            f.channel().closeFuture().sync();
            System.out.println("Proxy server has been shut down.");
        } catch (Exception e) {
            System.err.println("Exception occurred while running the proxy server:");
            e.printStackTrace();
        } finally {
            // Gracefully shut down event loop groups
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            System.out.println("Netty EventLoopGroups shut down gracefully.");
        }
    }

    // Simple VarInt reader
    private static int readVarInt(ByteBuf in) {
        int value = 0;
        int position = 0;
        while (true) {
            if (!in.isReadable()) {
                return -1; // Not enough data
            }
            byte b = in.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                return -1; // VarInt too long or malformed
            }
        }
    }

    // Helper method to convert ByteBuf to Hex String
    private static String byteBufToHex(ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
            sb.append(String.format("%02X ", buf.getByte(i)));
        }
        return sb.toString().trim();
    }

    static class MinecraftHandshakeCheckHandler extends ChannelInboundHandlerAdapter {
        private final String remoteHost;
        private final int remotePort;
        private ByteBuf buffer = Unpooled.buffer();
        private final DnsAddressResolverGroup resolverGroup;

        public MinecraftHandshakeCheckHandler(String remoteHost, int remotePort, DnsAddressResolverGroup resolverGroup) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.resolverGroup = resolverGroup;
            System.out.println("MinecraftHandshakeCheckHandler initialized for remote host: " + remoteHost + ":" + remotePort);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf in = (ByteBuf) msg;
            buffer.writeBytes(in);
            in.release();
            System.out.println("Data received from client. Buffer size: " + buffer.readableBytes());

            // Print the received data in Hex
            ByteBuf duplicate = buffer.copy();
            String hexData = byteBufToHex(duplicate);
            System.out.println("Received data (Hex): " + hexData);
            duplicate.release();

            buffer.markReaderIndex();

            if (!buffer.isReadable()) {
                System.out.println("Buffer is not readable. Waiting for more data.");
                buffer.resetReaderIndex();
                return;
            }

            // Convert the first byte to an unsigned int to determine packet type
            int firstByte = buffer.getByte(buffer.readerIndex()) & 0xFF;
            System.out.println("First byte of packet: " + firstByte);

            if (firstByte == 0xFE) { // Old ping packet indicator
                System.out.println("Detected old ping packet.");
                handleOldPing(ctx);
            } else {
                System.out.println("Detected new protocol packet.");
                handleNewProtocol(ctx);
            }
        }

        private void handleOldPing(ChannelHandlerContext ctx) {
            System.out.println("Handling old ping packet.");
            if (buffer.readableBytes() < 1) {
                System.out.println("Not enough data for old ping packet. Waiting for more data.");
                buffer.resetReaderIndex();
                return;
            }

            ByteBuf originalPacket = buffer.copy();
            buffer.clear();

            // Verify the ping packet starts with 0xFE
            byte pingByte = originalPacket.readByte();
            if ((pingByte & 0xFF) != 0xFE) {
                System.out.println("Invalid old ping packet. Closing connection.");
                ctx.close();
                return;
            }

            System.out.println("Getting client IP address.");
            InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            String clientIp = clientAddress.getAddress().getHostAddress();

            System.out.println("Injecting client IP into the packet.");
            ByteBuf modifiedPacket = injectClientIpAtStart(originalPacket, clientIp);
            originalPacket.release();

            System.out.println("Valid old ping packet received. Forwarding to remote server.");

            String hexData = byteBufToHex(modifiedPacket);
            System.out.println("Sending data with injected IP to remote server (Hex): " + hexData);

            connectToRemote(ctx, modifiedPacket);
        }

        private ByteBuf injectClientIpAtStart(ByteBuf originalPacket, String clientIp) {
            ByteBuf modifiedPacket = Unpooled.buffer();

            byte[] ipBytes = clientIp.getBytes();
            byte[] marker = "MCIP".getBytes();
            modifiedPacket.writeBytes(marker);
            modifiedPacket.writeByte(ipBytes.length);
            modifiedPacket.writeBytes(ipBytes);
            modifiedPacket.writeBytes(originalPacket);

            return modifiedPacket;
        }

        private void handleNewProtocol(ChannelHandlerContext ctx) {
            System.out.println("Handling new protocol packet.");
            int length = readVarInt(buffer);
            if (length == -1) {
                System.out.println("VarInt length not fully received. Waiting for more data.");
                buffer.resetReaderIndex();
                return;
            }

            int packetId = readVarInt(buffer);
            if (packetId == -1) {
                System.out.println("VarInt packet ID not fully received. Waiting for more data.");
                buffer.resetReaderIndex();
                return;
            }

            boolean isHandshake = (packetId == 0x00);
            System.out.println("Packet ID: " + packetId + " | Is handshake: " + isHandshake);

            if (!isHandshake) {
                System.out.println("Not a handshake packet. Closing connection.");
                ctx.close();
                return;
            }

            // It's a handshake packet; initiate connection to remote server
            System.out.println("Handshake packet detected. Initiating connection to remote server.");

            buffer.resetReaderIndex();
            ByteBuf originalPacket = buffer.copy();
            buffer.clear();

            System.out.println("Remote Address: " + ctx.channel().remoteAddress());
            InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            String clientIp = clientAddress.getAddress().getHostAddress();

            System.out.println("Injecting client IP into the packet.");
            ByteBuf modifiedPacket = injectClientIpAtStart(originalPacket, clientIp);
            originalPacket.release();

            String hexData = byteBufToHex(modifiedPacket);
            System.out.println("Sending handshake data with injected IP to remote server (Hex): " + hexData);

            connectToRemote(ctx, modifiedPacket);
        }

        private void connectToRemote(ChannelHandlerContext ctx, ByteBuf initialData) {
            System.out.println("Starting DNS resolution for remote host: " + remoteHost + ":" + remotePort);
            EventLoop eventLoop = ctx.channel().eventLoop();
            AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(eventLoop);

            resolver.resolve(InetSocketAddress.createUnresolved(remoteHost, remotePort)).addListener((Future<InetSocketAddress> future) -> {
                if (!future.isSuccess()) {
                    System.err.println("DNS resolution failed for " + remoteHost + ":" + remotePort);
                    future.cause().printStackTrace();
                    ctx.close();
                    return;
                }

                InetSocketAddress resolved = future.getNow();
                System.out.println("DNS resolution successful: " + resolved);

                Bootstrap b = new Bootstrap();
                b.group(eventLoop)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.AUTO_READ, false)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ChannelPipeline p = ch.pipeline();
                                p.addLast(new RelayHandler(ctx.channel()));
                                System.out.println("RelayHandler added to remote channel pipeline.");
                            }
                        });

                b.connect(resolved).addListener((ChannelFutureListener) cf -> {
                    if (!cf.isSuccess()) {
                        System.err.println("Failed to connect to remote server: " + resolved);
                        cf.cause().printStackTrace();
                        ctx.close();
                        return;
                    }

                    Channel remoteChannel = cf.channel();
                    System.out.println("Successfully connected to remote server: " + resolved);

                    remoteChannel.writeAndFlush(initialData).addListener((ChannelFutureListener) writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            System.err.println("Failed to forward initial data to remote server.");
                            writeFuture.cause().printStackTrace();
                            ctx.close();
                            remoteChannel.close();
                        } else {
                            System.out.println("Initial data forwarded to remote server.");
                        }
                    });

                    // ========= 改成「先移除再加」的關鍵修正 =========
                    eventLoop.execute(() -> {
                        // 確認 pipeline 中還有 "handshakeHandler" 就移除它
                        if (ctx.pipeline().get("handshakeHandler") != null) {
                            ctx.pipeline().remove("handshakeHandler");
                            ctx.pipeline().addLast("relay", new RelayHandler(remoteChannel));
                        }
                    });
                    // ==========================================

                    ctx.channel().config().setAutoRead(true);
                    remoteChannel.config().setAutoRead(true);
                    System.out.println("RelayHandler set up for bi-directional data forwarding.");
                });
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Exception in MinecraftHandshakeCheckHandler:");
            cause.printStackTrace();
            ctx.close();
        }
    }

    static class RelayHandler extends ChannelInboundHandlerAdapter {
        private final Channel targetChannel;

        public RelayHandler(Channel targetChannel) {
            this.targetChannel = targetChannel;
            System.out.println("RelayHandler initialized with target channel: " + targetChannel);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            targetChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    System.err.println("Failed to relay data to target channel.");
                    future.cause().printStackTrace();
                    ctx.close();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            System.out.println("Source channel inactive. Closing target channel.");
            targetChannel.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Exception in RelayHandler:");
            cause.printStackTrace();
            ctx.close();
        }

        // Helper method to convert ByteBuf to Hex String
        private static String byteBufToHex(ByteBuf buf) {
            StringBuilder sb = new StringBuilder();
            for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
                sb.append(String.format("%02X ", buf.getByte(i)));
            }
            return sb.toString().trim();
        }
    }
}