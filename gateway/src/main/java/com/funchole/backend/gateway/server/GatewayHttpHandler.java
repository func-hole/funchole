package com.funchole.backend.gateway.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;

@ChannelHandler.Sharable
public final class GatewayHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public GatewayHttpHandler(ObjectMapper objectMapper, DataSource dataSource) {
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
        if (request.method() != HttpMethod.GET) {
            writeJson(context, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of(
                    "success", false,
                    "message", "Only GET is supported in the gateway demo"
            ));
            return;
        }

        String path = sanitizePath(request.uri());

        if ("/health".equals(path)) {
            writeJson(context, HttpResponseStatus.OK, Map.of(
                    "success", true,
                    "service", "gateway",
                    "transport", "raw-netty",
                    "status", "ok"
            ));
            return;
        }

        if ("/demo/bootstrap-metadata".equals(path)) {
            writeJson(context, HttpResponseStatus.OK, Map.of(
                    "success", true,
                    "data", fetchBootstrapMetadata()
            ));
            return;
        }

        writeJson(context, HttpResponseStatus.NOT_FOUND, Map.of(
                "success", false,
                "message", "Route not found",
                "path", path
        ));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        writeText(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway error: " + cause.getMessage());
    }

    private Map<String, String> fetchBootstrapMetadata() throws SQLException {
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        select key, value
                        from app_metadata
                        where key = ?
                        """)
        ) {
            statement.setString(1, "bootstrap.version");
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("bootstrap.version metadata not found");
                }
                return Map.of(
                        "service", "gateway",
                        "key", resultSet.getString("key"),
                        "value", resultSet.getString("value")
                );
            }
        }
    }

    private String sanitizePath(String uri) {
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    private void writeJson(ChannelHandlerContext context, HttpResponseStatus status, Object payload) throws Exception {
        byte[] responseBody = objectMapper.writeValueAsBytes(payload);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(responseBody)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void writeText(ChannelHandlerContext context, HttpResponseStatus status, String body) {
        byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(responseBody)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
