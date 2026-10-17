package com.example.im.gateway;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.application.SendMessageHandler;
import com.example.im.message.domain.MessageRejectedException;
import com.example.im.message.domain.MessageType;
import com.example.im.message.domain.SendMessageCommand;
import com.example.im.message.domain.SendMessageResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class GatewayFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final ObjectMapper objectMapper;
    private final SendMessageHandler messages;
    private final GatewayConnectionRegistry connections;
    private final ImProperties.Gateway config;
    private final Executor businessExecutor;
    private final Semaphore gatewayInFlight;
    private final Counter operations;
    private final Counter rateLimited;
    private final Counter busy;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();
    private int connectionInFlight;
    private ScheduledFuture<?> slowClose;

    GatewayFrameHandler(ObjectMapper objectMapper, SendMessageHandler messages,
                        GatewayConnectionRegistry connections, ImProperties.Gateway config,
                        Executor businessExecutor, Semaphore gatewayInFlight, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.connections = connections;
        this.config = config;
        this.businessExecutor = businessExecutor;
        this.gatewayInFlight = gatewayInFlight;
        this.operations = meters.counter("im.gateway.operations.received");
        this.rateLimited = meters.counter("im.gateway.operations.rate_limited");
        this.busy = meters.counter("im.gateway.operations.busy");
        this.tokens = config.operationBurst();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            connections.register(context.channel());
            GatewayTicketService.Identity identity = context.channel().attr(GatewayAttributes.IDENTITY).get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("connectionId", context.channel().attr(GatewayAttributes.CONNECTION_ID).get());
            data.put("gatewayBootId", connections.bootId().toString());
            data.put("userId", identity.userId().toString());
            data.put("deviceId", identity.deviceId().toString());
            data.put("heartbeatIntervalMs", config.heartbeatInterval().toMillis());
            write(context, "READY", null, data);
            return;
        }
        if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            close(context, 4000, "IDLE_TIMEOUT");
            return;
        }
        super.userEventTriggered(context, event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (frame instanceof PingWebSocketFrame ping) {
            context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            close(context, 1003, "TEXT_FRAMES_REQUIRED");
            return;
        }
        if (!(frame instanceof TextWebSocketFrame text)) return;
        operations.increment();
        if (!takeToken()) {
            rateLimited.increment();
            error(context, null, "RATE_LIMITED", "Too many gateway operations", true, 1000L);
            return;
        }
        handleText(context, text.text());
    }

    private void handleText(ChannelHandlerContext context, String text) {
        String requestId = null;
        try {
            JsonNode envelope = objectMapper.readTree(text);
            if (envelope.path("v").asInt(-1) != 1) throw new ProtocolException("UNSUPPORTED_VERSION", "Protocol version must be 1");
            String operation = requiredText(envelope, "op");
            requestId = requiredText(envelope, "requestId");
            UUID.fromString(requestId);
            if (operation.equals("PING")) {
                write(context, "PONG", requestId, Map.of("serverTime", java.time.Instant.now().toString()));
            } else if (operation.equals("SEND")) {
                submitSend(context, requestId, envelope.path("data"));
            } else if (operation.equals("RECEIVED_ACK")) {
                write(context, "RECEIVED_ACK", requestId, Map.of("accepted", true));
            } else {
                throw new ProtocolException("UNSUPPORTED_OPERATION", "Unsupported operation: " + operation);
            }
        } catch (ProtocolException | IllegalArgumentException exception) {
            String code = exception instanceof ProtocolException value ? value.code : "INVALID_FRAME";
            error(context, requestId, code, exception.getMessage(), false, null);
        } catch (JacksonException exception) {
            error(context, requestId, "INVALID_JSON", "Frame must contain valid JSON", false, null);
        }
    }

    private void submitSend(ChannelHandlerContext context, String requestId, JsonNode data) {
        if (data.isMissingNode() || !data.isObject()) throw new ProtocolException("INVALID_FRAME", "SEND data is required");
        UUID conversationId = UUID.fromString(requiredText(data, "conversationId"));
        UUID clientMessageId = UUID.fromString(requiredText(data, "clientMsgId"));
        MessageType type = MessageType.valueOf(requiredText(data, "type"));
        JsonNode content = data.path("content");
        if (content.isMissingNode() || content.isNull()) throw new ProtocolException("INVALID_FRAME", "SEND content is required");
        List<UUID> attachmentIds = new ArrayList<>();
        JsonNode attachments = data.path("attachmentIds");
        if (!attachments.isMissingNode() && !attachments.isArray()) throw new ProtocolException("INVALID_FRAME", "attachmentIds must be an array");
        if (attachments.isArray()) {
            if (attachments.size() > 10) throw new ProtocolException("TOO_MANY_ATTACHMENTS", "At most 10 attachments are allowed");
            attachments.forEach(value -> attachmentIds.add(UUID.fromString(value.asString())));
        }
        if (connectionInFlight >= config.maxConnectionInFlight() || !gatewayInFlight.tryAcquire()) {
            busy.increment();
            error(context, requestId, "GATEWAY_BUSY", "Gateway request capacity is exhausted", true, 50L);
            return;
        }
        connectionInFlight++;
        GatewayTicketService.Identity identity = context.channel().attr(GatewayAttributes.IDENTITY).get();
        SendMessageCommand command = new SendMessageCommand(conversationId, identity.userId(),
                clientMessageId, type, content, attachmentIds);
        try {
            businessExecutor.execute(() -> {
                SendMessageResult result = null; Throwable failure = null;
                try { result = messages.handle(command); }
                catch (Throwable exception) { failure = exception; }
                SendMessageResult completed = result; Throwable thrown = failure;
                context.executor().execute(() -> completeSend(context, requestId, completed, thrown));
            });
        } catch (RejectedExecutionException exception) {
            connectionInFlight--;
            gatewayInFlight.release();
            busy.increment();
            error(context, requestId, "GATEWAY_BUSY", "Gateway business queue is full", true, 50L);
        }
    }

    private void completeSend(ChannelHandlerContext context, String requestId,
                              SendMessageResult result, Throwable failure) {
        connectionInFlight--;
        gatewayInFlight.release();
        if (!context.channel().isActive()) return;
        if (failure == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationId", result.conversationId().toString());
            data.put("clientMsgId", result.clientMessageId().toString());
            data.put("messageId", result.messageId().toString());
            data.put("seq", Long.toUnsignedString(result.sequence()));
            data.put("serverTime", result.serverTime().toString());
            data.put("replayed", result.replayed());
            write(context, "SEND_ACK", requestId, data);
        } else if (failure instanceof MessageRejectedException rejected) {
            error(context, requestId, rejected.code(), rejected.getMessage(), rejected.retryable(), null);
        } else if (failure instanceof ApiBusinessException business) {
            error(context, requestId, business.code(), business.getMessage(), business.retryable(), null);
        } else {
            error(context, requestId, "INTERNAL_ERROR", "Gateway operation failed", true, 1000L);
        }
    }

    private boolean takeToken() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(config.operationBurst(), tokens + elapsed * config.operationsPerSecond());
        lastRefillNanos = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) throw new ProtocolException("INVALID_FRAME", field + " is required");
        return value.asString();
    }

    private void error(ChannelHandlerContext context, String requestId, String code,
                       String message, boolean retryable, Long retryAfterMillis) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code); data.put("message", message); data.put("retryable", retryable);
        if (retryAfterMillis != null) data.put("retryAfterMs", retryAfterMillis);
        write(context, "ERROR", requestId, data);
    }

    private void write(ChannelHandlerContext context, String operation, String requestId, Object data) {
        if (!context.channel().isActive()) return;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", 1); envelope.put("op", operation);
        if (requestId != null) envelope.put("requestId", requestId);
        envelope.put("data", data);
        try {
            String encoded = objectMapper.writeValueAsString(envelope);
            long pending = context.channel().isWritable()
                    ? Math.max(0, config.writeHighWatermarkBytes() - context.channel().bytesBeforeUnwritable())
                    : config.writeLowWatermarkBytes() + context.channel().bytesBeforeWritable();
            if (pending + encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > config.maxPendingOutboundBytes()) {
                close(context, 4002, "OUTBOUND_BUFFER_LIMIT");
                return;
            }
            context.writeAndFlush(new TextWebSocketFrame(encoded));
        }
        catch (JacksonException exception) { close(context, 1011, "ENCODING_FAILED"); }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext context) throws Exception {
        if (!context.channel().isWritable() && slowClose == null) {
            Duration timeout = config.slowConnectionTimeout();
            slowClose = context.executor().schedule(() -> {
                if (!context.channel().isWritable()) close(context, 4002, "SLOW_CONNECTION");
                slowClose = null;
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } else if (context.channel().isWritable() && slowClose != null) {
            slowClose.cancel(false); slowClose = null;
        }
        super.channelWritabilityChanged(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (slowClose != null) slowClose.cancel(false);
        connections.unregister(context.channel());
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        close(context, 1011, "PROTOCOL_FAILURE");
    }

    private void close(ChannelHandlerContext context, int code, String reason) {
        if (context.channel().isActive()) context.writeAndFlush(new CloseWebSocketFrame(code, reason))
                .addListener(ignored -> context.close());
    }

    private static final class ProtocolException extends RuntimeException {
        private final String code;
        private ProtocolException(String code, String message) { super(message); this.code = code; }
    }
}
