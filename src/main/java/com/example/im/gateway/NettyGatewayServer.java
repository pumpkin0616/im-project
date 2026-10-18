package com.example.im.gateway;

import com.example.im.config.ImProperties;
import com.example.im.message.application.SendMessageHandler;
import com.example.im.read.ReadReceiptService;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public final class NettyGatewayServer implements SmartLifecycle {
  private final ImProperties.Gateway config;
  private final GatewayTicketService tickets;
  private final GatewayConnectionRegistry connections;
  private final SendMessageHandler messages;
  private final ReadReceiptService reads;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meters;
  private volatile boolean running;
  private EventLoopGroup boss;
  private EventLoopGroup workers;
  private ThreadPoolExecutor business;
  private Channel serverChannel;

  public NettyGatewayServer(ImProperties properties, GatewayTicketService tickets,
      GatewayConnectionRegistry connections, SendMessageHandler messages,
      ReadReceiptService reads, ObjectMapper objectMapper, MeterRegistry meters) {
    this.config = properties.gateway();
    this.tickets = tickets;
    this.connections = connections;
    this.messages = messages;
    this.reads = reads;
    this.objectMapper = objectMapper;
    this.meters = meters;
  }

  @Override
  public synchronized void start() {
    if (running)
      return;
    if (!config.enabled()) {
      running = true;
      return;
    }
    boss = new MultiThreadIoEventLoopGroup(config.bossThreads(),
        new DefaultThreadFactory("im-gateway-boss"), NioIoHandler.newFactory());
    workers = new MultiThreadIoEventLoopGroup(config.workerThreads(),
        new DefaultThreadFactory("im-gateway-io"), NioIoHandler.newFactory());
    business = new ThreadPoolExecutor(config.businessThreads(), config.businessThreads(),
        0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(config.businessQueueCapacity()),
        new DefaultThreadFactory("im-gateway-business"), new ThreadPoolExecutor.AbortPolicy());
    Semaphore gatewayInFlight = new Semaphore(config.maxGatewayInFlight());
    WebSocketServerProtocolConfig webSocket = WebSocketServerProtocolConfig.newBuilder()
        .websocketPath("/ws").checkStartsWith(true).allowExtensions(false)
        .maxFramePayloadLength(config.maxFrameBytes()).build();
    try {
      serverChannel = new ServerBootstrap().group(boss, workers)
          .channel(NioServerSocketChannel.class)
          .option(ChannelOption.SO_BACKLOG, 4096)
          .childOption(ChannelOption.TCP_NODELAY, true)
          .childOption(ChannelOption.SO_KEEPALIVE, true)
          .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
              new WriteBufferWaterMark(config.writeLowWatermarkBytes(), config.writeHighWatermarkBytes()))
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
              channel.pipeline().addLast("http", new HttpServerCodec());
              channel.pipeline().addLast("http-aggregate", new HttpObjectAggregator(16 * 1024));
              channel.pipeline().addLast("ticket-auth",
                  new GatewayHandshakeAuthHandler(tickets, config, business, meters));
              channel.pipeline().addLast("idle",
                  new IdleStateHandler(config.idleTimeout().toMillis(), 0, 0, TimeUnit.MILLISECONDS));
              channel.pipeline().addLast("websocket", new WebSocketServerProtocolHandler(webSocket));
              channel.pipeline().addLast("protocol", new GatewayFrameHandler(objectMapper, messages,
                  connections, reads, config, business, gatewayInFlight, meters));
            }
          }).bind(config.port()).syncUninterruptibly().channel();
      running = true;
    } catch (RuntimeException exception) {
      shutdownResources();
      throw exception;
    }
  }

  @Override
  public synchronized void stop() {
    if (!running)
      return;
    connections.closeAll();
    if (serverChannel != null)
      serverChannel.close().syncUninterruptibly();
    shutdownResources();
    running = false;
  }

  private void shutdownResources() {
    if (business != null)
      business.shutdownNow();
    if (workers != null)
      workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    if (boss != null)
      boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    business = null;
    workers = null;
    boss = null;
    serverChannel = null;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
