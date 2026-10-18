package com.example.im.gateway;

import com.example.im.config.ImProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class GatewayHandshakeAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  private final GatewayTicketService tickets;
  private final Set<String> allowedOrigins;
  private final Executor executor;
  private final Counter rejected;

  GatewayHandshakeAuthHandler(GatewayTicketService tickets, ImProperties.Gateway config,
      Executor executor, MeterRegistry meters) {
    this.tickets = tickets;
    this.allowedOrigins = Set.copyOf(config.allowedOrigins());
    this.executor = executor;
    this.rejected = meters.counter("im.gateway.handshakes.rejected");
  }

  @Override
  protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
    QueryStringDecoder query = new QueryStringDecoder(request.uri());
    if (request.method() != HttpMethod.GET || !query.path().equals("/ws")) {
      reject(context, HttpResponseStatus.NOT_FOUND, "WebSocket endpoint not found");
      return;
    }
    String origin = request.headers().get(HttpHeaderNames.ORIGIN);
    if (origin == null || !allowedOrigins.contains(origin)) {
      reject(context, HttpResponseStatus.FORBIDDEN, "Origin is not allowed");
      return;
    }
    List<String> values = query.parameters().get("ticket");
    if (values == null || values.size() != 1) {
      reject(context, HttpResponseStatus.UNAUTHORIZED, "Connection ticket is required");
      return;
    }
    request.retain();
    try {
      CompletableFuture.supplyAsync(() -> tickets.consume(values.getFirst()), executor)
          .whenComplete((identity, failure) -> context.executor().execute(() -> {
            if (!context.channel().isActive()) {
              ReferenceCountUtil.release(request);
              return;
            }
            if (failure != null || identity == null) {
              ReferenceCountUtil.release(request);
              reject(context, HttpResponseStatus.UNAUTHORIZED, "Connection ticket is invalid or expired");
              return;
            }
            context.channel().attr(GatewayAttributes.IDENTITY).set(identity);
            context.channel().attr(GatewayAttributes.CONNECTION_ID).set(UUID.randomUUID().toString());
            context.pipeline().remove(this);
            context.fireChannelRead(request);
          }));
    } catch (RejectedExecutionException exception) {
      ReferenceCountUtil.release(request);
      reject(context, HttpResponseStatus.SERVICE_UNAVAILABLE, "Gateway is busy");
    }
  }

  private void reject(ChannelHandlerContext context, HttpResponseStatus status, String message) {
    rejected.increment();
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
        Unpooled.wrappedBuffer(bytes));
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
    HttpUtil.setContentLength(response, bytes.length);
    context.writeAndFlush(response).addListener(ignored -> context.close());
  }
}
