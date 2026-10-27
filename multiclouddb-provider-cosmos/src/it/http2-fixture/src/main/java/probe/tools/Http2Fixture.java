// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package probe.tools;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;

public final class Http2Fixture {
    private static volatile String endpoint;

    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, "probe-only".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "probe-only".toCharArray());
        SslContext ssl = SslContextBuilder.forServer(managers).sslProvider(SslProvider.JDK)
                .applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1)).build();
        EventLoopGroup accept = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup(2);
        try {
            Channel server = new ServerBootstrap().group(accept, workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(ssl.newHandler(channel.alloc()));
                            channel.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                                @Override protected void configurePipeline(ChannelHandlerContext context, String protocol) {
                                    System.out.println("NEGOTIATED_PROTOCOL " + protocol);
                                    if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                        context.pipeline().addLast(Http2FrameCodecBuilder.forServer().build(),
                                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                                    @Override protected void initChannel(Channel stream) {
                                                        stream.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true),
                                                                new HttpObjectAggregator(65536), responder("h2"));
                                                    }
                                                }));
                                    } else {
                                        context.pipeline().addLast(new HttpServerCodec(),
                                                new HttpObjectAggregator(65536), responder(protocol));
                                    }
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            endpoint = "https://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort() + "/";
            Files.writeString(Path.of(args[1]), endpoint);
            workers.schedule(() -> server.close(), 45, TimeUnit.SECONDS);
            server.closeFuture().sync();
        } finally {
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            accept.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }

    private static ChannelHandler responder(String protocol) {
        return new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
                System.out.println("FIXTURE_REQUEST " + protocol + " " + request.method() + " " + request.uri());
                String json = "{\"id\":\"packaging-probe\",\"_rid\":\"probe\","
                        + "\"writableLocations\":[{\"name\":\"West US\",\"databaseAccountEndpoint\":\"" + endpoint + "\"}],"
                        + "\"readableLocations\":[{\"name\":\"West US\",\"databaseAccountEndpoint\":\"" + endpoint + "\"}],"
                        + "\"enableMultipleWriteLocations\":false,"
                        + "\"userConsistencyPolicy\":{\"defaultConsistencyLevel\":\"Session\"},"
                        + "\"userReplicationPolicy\":{\"maxReplicaSetSize\":4,\"minReplicaSetSize\":3},"
                        + "\"systemReplicationPolicy\":{\"maxReplicaSetSize\":4,\"minReplicaSetSize\":3}}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                response.headers().set("x-ms-activity-id", "00000000-0000-0000-0000-000000000001");
                context.writeAndFlush(response);
            }
        };
    }
}
