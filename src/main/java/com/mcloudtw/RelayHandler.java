package com.mcloudtw;

import io.netty.channel.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LogManager.getLogger(RelayHandler.class);
    private final Channel targetChannel;

    public RelayHandler(Channel targetChannel) {
        this.targetChannel = targetChannel;
        LOGGER.info("RelayHandler initialized with target channel: " + targetChannel);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        targetChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) return;
            LOGGER.error("Failed to relay data to target channel.");
            future.cause().printStackTrace();
            ctx.close();
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOGGER.info("Source channel inactive. Closing target channel.");
        targetChannel.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error("Exception in RelayHandler:");
        cause.printStackTrace();
        ctx.close();
    }
}
