package com.funchole.backend.gateway.server;

import com.funchole.backend.gateway.GatewayRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SniHandler;

public class GatewayChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final GatewayHttpHandler gatewayHttpHandler;
    private final GatewayRegistry gatewayRegistry;

    public GatewayChannelInitializer(GatewayRegistry gatewayRegistry, GatewayHttpHandler gatewayHttpHandler) {
        this.gatewayRegistry = gatewayRegistry;
        this.gatewayHttpHandler = gatewayHttpHandler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast(new SniHandler(gatewayRegistry.sslContextMapping()))
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(gatewayHttpHandler);
    }
}
