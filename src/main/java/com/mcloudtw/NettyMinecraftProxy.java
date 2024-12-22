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

import java.security.Security;

/**
 * Netty Minecraft Proxy (Main)
 */
public class NettyMinecraftProxy {

    // Proxy 監聽設定
    private static final int LISTEN_PORT = 25565;
    // 後端伺服器設定
    private static final String REMOTE_HOST = "tw1.mcloudtw.com";
    private static final int REMOTE_PORT = 25565;

    public static void main(String[] args) throws InterruptedException {
        // 1. 停用 JVM 預設 DNS 快取
        disableJavaDNSCache();

        // 2. 初始化 EventLoopGroup
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        System.out.println("Netty EventLoopGroups initialized.");

        // 3. 建立 DNS ResolverGroup
        DNSResolverConfig resolverConfig = new DNSResolverConfig();
        System.out.println("Netty DNS resolver configured with minimal caching.");

        try {
            // 4. 設置 Netty ServerBootstrap
            ServerBootstrap b = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // Server 端使用 INFO 級別日誌
                    .handler(new LoggingHandler(LogLevel.INFO))
                    // Client 連入後使用 DEBUG 級別日誌
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("debugLogger", new LoggingHandler(LogLevel.DEBUG));
                            // 加入 Handshake 處理器，檢查並注入 Client IP
                            p.addLast("handshakeHandler",
                                    new MinecraftHandshakeCheckHandler(REMOTE_HOST, REMOTE_PORT, resolverConfig.getResolverGroup()));
                            System.out.println("Initialized channel pipeline for new connection.");
                        }
                    });

            // 5. 綁定服務埠並啟動
            ChannelFuture f = b.bind(LISTEN_PORT).sync();
            System.out.println("[11:45:14 INFO]: Done (114.514s)! Proxy listening on port " + LISTEN_PORT);

            // 6. 等待關閉
            f.channel().closeFuture().sync();
            System.out.println("Proxy server has been shut down.");
        } catch (Exception e) {
            System.err.println("Exception occurred while running the proxy server:");
            e.printStackTrace();
        } finally {
            // 7. 優雅關閉
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            System.out.println("Netty EventLoopGroups shut down gracefully.");
        }
    }

    /**
     * 停用 Java DNS 快取（將 ttl & negative ttl 都設成 0）
     */
    private static void disableJavaDNSCache() {
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        System.out.println("Java DNS caching disabled.");
    }

}
