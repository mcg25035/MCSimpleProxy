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
                    .handler(new LoggingHandler(LogLevel.INFO)) // Add logging handler to server bootstrap
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new LoggingHandler(LogLevel.DEBUG)); // Add debug logging for each connection
                            p.addLast(new MinecraftHandshakeCheckHandler(REMOTE_HOST, REMOTE_PORT, resolverGroup));
                            System.out.println("Initialized channel pipeline for new connection.");
                        }
                    });

            // Bind the server to the specified port
            ChannelFuture f = b.bind(LISTEN_PORT).sync();
            System.out.println("Done (codingbear's minecraft proxy)!");
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
            // Old ping packets typically start with 0xFE, adjust based on protocol
            if (buffer.readableBytes() < 1) { // Assuming ping packet has at least 1 byte
                System.out.println("Not enough data for old ping packet. Waiting for more data.");
                buffer.resetReaderIndex();
                return;
            }

            byte pingByte = buffer.readByte();
            if ((pingByte & 0xFF) != 0xFE) {
                System.out.println("Invalid old ping packet. Closing connection.");
                ctx.close();
                return;
            }

            System.out.println("Valid old ping packet received. Forwarding to remote server.");
            // Forward the ping packet to the remote server
            ByteBuf pingPacket = Unpooled.wrappedBuffer(new byte[]{pingByte});
            // Print the data being sent
            String hexData = byteBufToHex(pingPacket);
            System.out.println("Sending data to remote server (Hex): " + hexData);

            connectToRemote(ctx, pingPacket);
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

            // Reset the reader index to the start to forward the entire packet
            buffer.resetReaderIndex();

            // Copy the entire buffer to forward to the remote server
            ByteBuf pendingData = buffer.copy();
            buffer.clear(); // Clear buffer for future reads

            // Print the data being sent
            String hexData = byteBufToHex(pendingData);
            System.out.println("Sending handshake data to remote server (Hex): " + hexData);

            connectToRemote(ctx, pendingData);
        }

        private void connectToRemote(ChannelHandlerContext ctx, ByteBuf initialData) {
            System.out.println("Starting DNS resolution for remote host: " + remoteHost + ":" + remotePort);
            EventLoop eventLoop = ctx.channel().eventLoop();
            AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(eventLoop);

            // Start asynchronous DNS resolution
            resolver.resolve(InetSocketAddress.createUnresolved(remoteHost, remotePort)).addListener((Future<InetSocketAddress> future) -> {
                if (!future.isSuccess()) {
                    System.err.println("DNS resolution failed for " + remoteHost + ":" + remotePort);
                    future.cause().printStackTrace();
                    ctx.close();
                    return;
                }

                InetSocketAddress resolved = future.getNow();
                System.out.println("DNS resolution successful: " + resolved);

                // Set up a new Bootstrap to connect to the resolved remote server
                Bootstrap b = new Bootstrap();
                b.group(ctx.channel().eventLoop())
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

                // Connect to the remote server
                b.connect(resolved).addListener((ChannelFutureListener) cf -> {
                    if (!cf.isSuccess()) {
                        System.err.println("Failed to connect to remote server: " + resolved);
                        cf.cause().printStackTrace();
                        ctx.close();
                        return;
                    }

                    Channel remoteChannel = cf.channel();
                    System.out.println("Successfully connected to remote server: " + resolved);

                    // Forward the initial data to the remote server
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

                    // Replace the current handler with RelayHandler for bi-directional data forwarding
                    ctx.pipeline().replace(MinecraftHandshakeCheckHandler.this, "relay", new RelayHandler(remoteChannel));
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
//            System.out.println("Relaying data from source to target channel.");

            // Convert the data to Hex for debugging
//            ByteBuf buf = (ByteBuf) msg;
//            String hexData = byteBufToHex(buf);
//            System.out.println("Sending data to target server (Hex): " + hexData);

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
