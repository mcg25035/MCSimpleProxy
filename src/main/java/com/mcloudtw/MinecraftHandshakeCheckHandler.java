package com.mcloudtw;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.resolver.AddressResolver;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 主要用於檢查是否為舊版 Ping (0xFE) 或新版本協議 (Handshake 0x00)，
 * 並在連線前注入客戶端 IP。
 * 另外，透過 pendingQueue 機制，避免還沒連線完畢時就漏掉後續資料。
 */
public class MinecraftHandshakeCheckHandler extends ChannelInboundHandlerAdapter {

    private static final String CLIENT_IP_MARKER = "MCIP";

    private final String remoteHost;
    private final int remotePort;
    private final DnsAddressResolverGroup resolverGroup;

    // 你原本就有的 buffer，用來做「舊/新協議」檢查
    private final ByteBuf buffer = Unpooled.buffer();

    // 新增一個 queue，用來暫存所有還沒轉發到後端的資料
    private final Queue<ByteBuf> pendingQueue = new LinkedList<>();

    private ChannelHandlerContext ctxRef;  // 用來連線成功後操作 pipeline

    public MinecraftHandshakeCheckHandler(String remoteHost, int remotePort, DnsAddressResolverGroup resolverGroup) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.resolverGroup = resolverGroup;
        System.out.println("MinecraftHandshakeCheckHandler initialized for remote host: "
                + remoteHost + ":" + remotePort);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 記下 context，以便在 connectToRemote 完成後能操作 pipeline
        this.ctxRef = ctx;
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 1. 先把客戶端送進來的資料放到 pendingQueue，以免還沒連上遠端就先漏了
        ByteBuf in = (ByteBuf) msg;
        // 為了保險，retain 一份放到 queue
        pendingQueue.add(in.retainedDuplicate());

        // 再把資料累積到你原本用來做判斷的 buffer
        buffer.writeBytes(in);
        // 釋放自己這份 (注意 retainedDuplicate 了)
        in.release();

        // 2. 以下就是你原本 channelRead 內的檢查流程
        System.out.println("Data received from client. Buffer size: " + buffer.readableBytes());

        // 觀察 Hex
        ByteBuf duplicate = buffer.copy();
        String hexData = ByteBufUtils.toHexString(duplicate);
        duplicate.release();
        System.out.println("Received data (Hex): " + hexData);

        buffer.markReaderIndex();
        if (!buffer.isReadable()) {
            System.out.println("Buffer is not readable. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        // 判斷第一 Byte (0xFE => 舊版 Ping)
        int firstByte = buffer.getByte(buffer.readerIndex()) & 0xFF;
        System.out.println("First byte of packet: " + firstByte);

        if (firstByte == 0xFE) {
            System.out.println("Detected old ping packet.");
            handleOldPing(ctx);
        } else {
            System.out.println("Detected new protocol packet.");
            handleNewProtocol(ctx);
        }
    }

    /**
     * 處理舊版的 Ping 封包 (0xFE)
     */
    private void handleOldPing(ChannelHandlerContext ctx) {
        if (buffer.readableBytes() < 1) {
            System.out.println("Not enough data for old ping packet. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        ByteBuf originalPacket = buffer.copy();
        buffer.clear();

        byte pingByte = originalPacket.readByte();
        if ((pingByte & 0xFF) != 0xFE) {
            System.out.println("Invalid old ping packet. Closing connection.");
            ctx.close();
            originalPacket.release();
            return;
        }

        // 取得客戶端 IP 並注入
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        ByteBuf modifiedPacket = injectClientIpAtStart(originalPacket, clientIp);
        originalPacket.release();

        String hexData = ByteBufUtils.toHexString(modifiedPacket);
        System.out.println("Old ping packet with injected IP (Hex): " + hexData);

        connectToRemote(ctx, modifiedPacket);
    }

    /**
     * 處理 Minecraft 新版協議的封包（主要關心是否為 Handshake packetId == 0x00）
     */
    private void handleNewProtocol(ChannelHandlerContext ctx) {
        int length = VarIntUtils.readVarInt(buffer);
        if (length == -1) {
            System.out.println("VarInt length not fully received. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        int packetId = VarIntUtils.readVarInt(buffer);
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

        System.out.println("Packet before injection: " + ByteBufUtils.toHexString(buffer));

        buffer.resetReaderIndex();
        ByteBuf originalPacket = buffer.copy();
        buffer.clear();

        // 注入客戶端 IP
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        ByteBuf modifiedPacket = injectClientIpAtStart(originalPacket, clientIp);
        originalPacket.release();

        String hexData = ByteBufUtils.toHexString(modifiedPacket);
        System.out.println("Handshake packet with injected IP (Hex): " + hexData);

        connectToRemote(ctx, modifiedPacket);
    }

    /**
     * 將客戶端 IP 寫入封包前綴
     */
    private ByteBuf injectClientIpAtStart(ByteBuf originalPacket, String clientIp) {
        ByteBuf modifiedPacket = Unpooled.buffer();
        modifiedPacket.writeBytes(CLIENT_IP_MARKER.getBytes());
        byte[] ipBytes = clientIp.getBytes();
        modifiedPacket.writeByte(ipBytes.length);
        modifiedPacket.writeBytes(ipBytes);
        modifiedPacket.writeBytes(originalPacket);
        return modifiedPacket;
    }

    /**
     * 執行 DNS 解析，並與遠端伺服器建構連線；成功連線後轉發 initialData
     */
    private void connectToRemote(ChannelHandlerContext ctx, ByteBuf initialData) {
        System.out.println("Starting DNS resolution for remote host: " + remoteHost + ":" + remotePort);
        EventLoop eventLoop = ctx.channel().eventLoop();
        AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(eventLoop);

        resolver.resolve(InetSocketAddress.createUnresolved(remoteHost, remotePort))
                .addListener((Future<InetSocketAddress> future) -> {
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
                            .handler(new ChannelInitializer<Channel>() {
                                @Override
                                protected void initChannel(Channel ch) {
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

                        // 先把最初的那個 initialData 寫到遠端
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

                        // ========= 關鍵：把 pendingQueue 裡面尚未處理的資料一併丟過去 =========
                        flushPendingQueueToRemote(remoteChannel);

                        // 之後再修改 pipeline，改用 RelayHandler 雙向轉發
                        eventLoop.execute(() -> {
                            if (ctxRef.pipeline().get("handshakeHandler") != null) {
                                ctxRef.pipeline().remove("handshakeHandler");
                                ctxRef.pipeline().addLast("relay", new RelayHandler(remoteChannel));
                            }
                        });
                        // ==================================================================

                        ctx.channel().config().setAutoRead(true);
                        remoteChannel.config().setAutoRead(true);
                        System.out.println("RelayHandler set up for bi-directional data forwarding.");
                    });
                });
    }

    /**
     * 一次性把 pendingQueue 裡所有「尚未送」到後端的資料，全部 writeAndFlush。
     */
    private void flushPendingQueueToRemote(Channel remoteChannel) {
        System.out.println("Flushing pendingQueue to remote...");
        while (!pendingQueue.isEmpty()) {
            ByteBuf queuedData = pendingQueue.poll();
            if (queuedData != null && queuedData.isReadable()) {
                // 這裡要特別小心，因為 queuedData 是我們 retainedDuplicate() 來的
                // 如果這裡要繼續用，就不建議再 copy，直接 forward。
                remoteChannel.writeAndFlush(queuedData).addListener(f -> {
                    if (!f.isSuccess()) {
                        System.err.println("Failed to flush queued data to remote.");
                        f.cause().printStackTrace();
                    }
                });
            } else if (queuedData != null) {
                // 若不可讀就直接釋放
                queuedData.release();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Exception in MinecraftHandshakeCheckHandler:");
        cause.printStackTrace();
        ctx.close();
    }
}
