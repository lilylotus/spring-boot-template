package org.example.simple.util.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * 测试用本地 TCP 服务端，绑定 {@code 127.0.0.1} 随机端口，按模式模拟正常回显、回写错误字节或不回显。
 */
final class LocalEchoTestServer implements AutoCloseable {

    /** 服务端行为模式。 */
    enum Mode {
        /** 原样回显。 */
        ECHO,
        /** 每个字节取反后回写。 */
        CORRUPT,
        /** 接收但不回显。 */
        SILENT
    }

    /** IO 线程组。 */
    private final EventLoopGroup group;

    /** 监听通道。 */
    private final Channel serverChannel;

    /** 启动指定模式的服务端。 */
    LocalEchoTestServer(Mode mode) {
        group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        serverChannel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new ServerHandler(mode));
                    }
                })
                .bind("127.0.0.1", 0)
                .syncUninterruptibly()
                .channel();
    }

    /** 实际监听端口。 */
    int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public void close() {
        serverChannel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /** 按模式处理入站字节。 */
    private static final class ServerHandler extends ChannelInboundHandlerAdapter {

        /** 服务端行为模式。 */
        private final Mode mode;

        /** 创建处理器。 */
        ServerHandler(Mode mode) {
            this.mode = mode;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            switch (mode) {
                case ECHO -> ctx.writeAndFlush(buf);
                case CORRUPT -> {
                    for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
                        buf.setByte(i, ~buf.getByte(i));
                    }
                    ctx.writeAndFlush(buf);
                }
                default -> buf.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
