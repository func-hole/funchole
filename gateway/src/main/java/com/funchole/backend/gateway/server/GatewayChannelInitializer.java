package com.funchole.backend.gateway.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class GatewayChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final GatewayHttpHandler gatewayHttpHandler;

    public GatewayChannelInitializer(GatewayHttpHandler gatewayHttpHandler) {
        this.gatewayHttpHandler = gatewayHttpHandler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(gatewayHttpHandler);
    }
}
