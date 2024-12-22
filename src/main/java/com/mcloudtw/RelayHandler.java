package com.mcloudtw;

import io.netty.channel.*;

/**
 * 負責雙向轉發資料的 Handler
 */
public class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel targetChannel;

    public RelayHandler(Channel targetChannel) {
        this.targetChannel = targetChannel;
        System.out.println("RelayHandler initialized with target channel: " + targetChannel);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 將收到的資料原封不動轉發到目標管道
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
        // 任一方關閉時，另一方也關閉
        System.out.println("Source channel inactive. Closing target channel.");
        targetChannel.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Exception in RelayHandler:");
        cause.printStackTrace();
        ctx.close();
    }
}
