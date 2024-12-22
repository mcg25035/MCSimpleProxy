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

// Log4J imports
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 主要用於檢查是否為舊版 Ping (0xFE) 或新版本協議 (Handshake 0x00)，
 * 並在連線前注入客戶端 IP。
 * 另外，透過 pendingQueue 機制，避免還沒連線完畢時就漏掉後續資料。
 */
public class MinecraftHandshakeCheckHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LogManager.getLogger(MinecraftHandshakeCheckHandler.class);
    private static final String CLIENT_IP_MARKER = "MCIP";

    private final String remoteHost;
    private final int remotePort;
    private final DnsAddressResolverGroup resolverGroup;
    private boolean firstPacketSent = false;

    // 你原本就有的 buffer，用來做「舊/新協議」檢查
    private final ByteBuf buffer = Unpooled.buffer();

    // 新增一個 queue，用來暫存所有還沒轉發到後端的資料
    private final Queue<ByteBuf> pendingQueue = new LinkedList<>();

    private ChannelHandlerContext ctxRef;  // 用來連線成功後操作 pipeline

    public MinecraftHandshakeCheckHandler(String remoteHost, int remotePort, DnsAddressResolverGroup resolverGroup) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.resolverGroup = resolverGroup;
        logger.info("MinecraftHandshakeCheckHandler initialized for remote host: {}:{}", remoteHost, remotePort);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 記下 context，以便在 connectToRemote 完成後能操作 pipeline
        this.ctxRef = ctx;
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        logger.debug("Data received from client. Buffer: {}", ByteBufUtils.toHexString(in));

        if (firstPacketSent) {
            pendingQueue.add(in.retainedDuplicate());
            return;
        }

        firstPacketSent = true;

        // 再把資料累積到你原本用來做判斷的 buffer
        buffer.writeBytes(in);
        // 釋放自己這份 (注意 retainedDuplicate 了)
        in.release();

        // 2. 以下就是你原本 channelRead 內的檢查流程
        logger.debug("Data received from client. Buffer size: {}", buffer.readableBytes());

        // 觀察 Hex
        ByteBuf duplicate = buffer.copy();
        String hexData = ByteBufUtils.toHexString(duplicate);
        duplicate.release();
        logger.debug("Received data (Hex): {}", hexData);

        buffer.markReaderIndex();
        if (!buffer.isReadable()) {
            logger.debug("Buffer is not readable. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        // 判斷第一 Byte (0xFE => 舊版 Ping)
        int firstByte = buffer.getByte(buffer.readerIndex()) & 0xFF;
        logger.debug("First byte of packet: {}", firstByte);

        if (firstByte == 0xFE) {
            logger.info("Detected old ping packet.");
            handleOldPing(ctx);
        } else {
            logger.info("Detected new protocol packet.");
            handleNewProtocol(ctx);
        }
    }

    /**
     * 處理舊版的 Ping 封包 (0xFE)
     */
    private void handleOldPing(ChannelHandlerContext ctx) {
        if (buffer.readableBytes() < 1) {
            logger.debug("Not enough data for old ping packet. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        ByteBuf originalPacket = buffer.copy();
        buffer.clear();

        byte pingByte = originalPacket.readByte();
        if ((pingByte & 0xFF) != 0xFE) {
            logger.warn("Invalid old ping packet. Closing connection.");
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
        logger.debug("Old ping packet with injected IP (Hex): {}", hexData);

        connectToRemote(ctx, modifiedPacket);
    }

    /**
     * 處理 Minecraft 新版協議的封包（主要關心是否為 Handshake packetId == 0x00）
     */
    private void handleNewProtocol(ChannelHandlerContext ctx) {
        int length = VarIntUtils.readVarInt(buffer);
        if (length == -1) {
            logger.debug("VarInt length not fully received. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        int packetId = VarIntUtils.readVarInt(buffer);
        if (packetId == -1) {
            logger.debug("VarInt packet ID not fully received. Waiting for more data.");
            buffer.resetReaderIndex();
            return;
        }

        boolean isHandshake = (packetId == 0x00);
        logger.debug("Packet ID: {} | Is handshake: {}", packetId, isHandshake);

        if (!isHandshake) {
            logger.warn("Not a handshake packet. Closing connection.");
            ctx.close();
            return;
        }

        logger.debug("Packet before injection: {}", ByteBufUtils.toHexString(buffer));

        buffer.resetReaderIndex();
        ByteBuf originalPacket = buffer.copy();
        buffer.clear();

        // 注入客戶端 IP
        InetSocketAddress clientAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String clientIp = clientAddress.getAddress().getHostAddress();
        ByteBuf modifiedPacket = injectClientIpAtStart(originalPacket, clientIp);
        originalPacket.release();

        String hexData = ByteBufUtils.toHexString(modifiedPacket);
        logger.debug("Handshake packet with injected IP (Hex): {}", hexData);

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
        logger.info("Starting DNS resolution for remote host: {}:{}", remoteHost, remotePort);
        EventLoop eventLoop = ctx.channel().eventLoop();
        AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(eventLoop);

        // 執行 DNS 解析
        resolver.resolve(InetSocketAddress.createUnresolved(remoteHost, remotePort))
                .addListener((Future<InetSocketAddress> dnsFuture) -> {
                    if (!dnsFuture.isSuccess()) {
                        handleDnsFailure(dnsFuture.cause(), ctx);
                        return;
                    }
                    // 若成功解析，繼續進行連線
                    InetSocketAddress resolvedAddress = dnsFuture.getNow();
                    handleDnsSuccess(resolvedAddress, ctx, eventLoop, initialData);
                });
    }

    /** DNS 解析失敗時的處理 */
    private void handleDnsFailure(Throwable cause, ChannelHandlerContext ctx) {
        logger.error("DNS resolution failed for {}:{}", remoteHost, remotePort, cause);
        ctx.close();
    }

    /** DNS 解析成功時，使用解析出來的位址去連線 */
    private void handleDnsSuccess(InetSocketAddress resolvedAddress, ChannelHandlerContext ctx, EventLoop eventLoop, ByteBuf initialData) {
        logger.info("DNS resolution successful: {}", resolvedAddress);

        // 建立 Bootstrap，準備連線
        Bootstrap bootstrap = createRemoteBootstrap(eventLoop, ctx);

        // 與遠端建立連線
        bootstrap.connect(resolvedAddress).addListener((ChannelFutureListener) connectFuture -> {
            if (!connectFuture.isSuccess()) {
                handleConnectFailure(connectFuture.cause(), resolvedAddress, ctx);
                return;
            }
            // 連線成功後處理
            handleConnectSuccess(connectFuture.channel(), ctx, eventLoop, initialData);
        });
    }

    /** 建立與遠端連線所需的 Bootstrap */
    private Bootstrap createRemoteBootstrap(EventLoop eventLoop, ChannelHandlerContext ctx) {
        return new Bootstrap()
                .group(eventLoop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new RelayHandler(ctx.channel()));
                        logger.debug("RelayHandler added to remote channel pipeline.");
                    }
                });
    }

    /** 與遠端連線失敗時的處理 */
    private void handleConnectFailure(Throwable cause, InetSocketAddress resolvedAddress, ChannelHandlerContext ctx) {
        logger.error("Failed to connect to remote server: {}", resolvedAddress, cause);
        ctx.close();
    }

    /** 與遠端連線成功後，轉發 initialData，並處理其他工作 */
    private void handleConnectSuccess(Channel remoteChannel, ChannelHandlerContext ctx, EventLoop eventLoop, ByteBuf initialData) {
        logger.info("Successfully connected to remote server: {}", remoteChannel.remoteAddress());

        // 先把最初的 initialData 寫到遠端
        remoteChannel.writeAndFlush(initialData).addListener((ChannelFutureListener) writeFuture -> {
            if (writeFuture.isSuccess()) {
                logger.debug("Initial data forwarded to remote server.");
                return;
            }
            logger.error("Failed to forward initial data to remote server.", writeFuture.cause());
            ctx.close();
            remoteChannel.close();
        });

        // 將 pendingQueue 裡面尚未處理的資料一併丟過去
        flushPendingQueueToRemote(remoteChannel);

        // 之後再修改 pipeline，改用 RelayHandler 進行雙向轉發
        eventLoop.execute(() -> {
            if (ctxRef.pipeline().get("handshakeHandler") == null) return;
            ctxRef.pipeline().remove("handshakeHandler");
            ctxRef.pipeline().addLast("relay", new RelayHandler(remoteChannel));
        });

        // 啟用雙向自動讀取
        ctx.channel().config().setAutoRead(true);
        remoteChannel.config().setAutoRead(true);

        logger.info("RelayHandler set up for bi-directional data forwarding.");
    }

    /**
     * 一次性把 pendingQueue 裡所有「尚未送」到後端的資料，全部 writeAndFlush。
     */
    private void flushPendingQueueToRemote(Channel remoteChannel) {
        logger.debug("Flushing pendingQueue to remote...");

        ByteBuf queuedData;
        while ((queuedData = pendingQueue.poll()) != null) {
            // 先判斷是否可讀，不可讀就直接釋放
            if (!queuedData.isReadable()) {
                queuedData.release();
                continue;
            }

            // 若可讀則直接 forward 到 remoteChannel
            remoteChannel.writeAndFlush(queuedData).addListener(writeFuture -> {
                if (writeFuture.isSuccess()) return;
                logger.error("Failed to flush queued data to remote.", writeFuture.cause());
            });
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in MinecraftHandshakeCheckHandler:", cause);
        ctx.close();
    }
}
