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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.security.Security;

/**
 * Netty Minecraft Proxy (Main)
 */
public class NettyMinecraftProxy {
    private static final int LISTEN_PORT = 25565;
    private static final String REMOTE_HOST = "tw1.mcloudtw.com";
    private static final int REMOTE_PORT = 25565;

    private static final Logger LOGGER = LogManager.getLogger(NettyMinecraftProxy.class);


    public static void main(String[] args) throws InterruptedException {
        disableJavaDNSCache();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        LOGGER.info("Netty EventLoopGroups initialized.");

        DNSResolverConfig resolverConfig = new DNSResolverConfig();
        LOGGER.info("Netty DNS resolver configured with minimal caching.");

        try {
            ServerBootstrap b = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("debugLogger", new LoggingHandler(LogLevel.DEBUG));
                            p.addLast("handshakeHandler",
                                    new MinecraftHandshakeCheckHandler(REMOTE_HOST, REMOTE_PORT, resolverConfig.getResolverGroup()));
                            LOGGER.info("Initialized channel pipeline for new connection.");
                        }
                    });

            ChannelFuture f = b.bind(LISTEN_PORT).sync();
            LOGGER.info("[11:45:14 INFO]: Done (114.514s)! Proxy listening on port " + LISTEN_PORT);

            f.channel().closeFuture().sync();
            LOGGER.info("Proxy server has been shut down.");
        } catch (Exception e) {
            LOGGER.error("Exception occurred while running the proxy server:");
            e.printStackTrace();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            LOGGER.info("Netty EventLoopGroups shut down gracefully.");
        }
    }

    private static void disableJavaDNSCache() {
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        LOGGER.info("Java DNS caching disabled.");
    }

}
