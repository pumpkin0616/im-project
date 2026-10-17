package com.example.im.message.application;

import com.example.im.config.ImProperties;
import com.example.im.message.domain.MessageRejectedException;
import com.example.im.message.domain.SendMessageCommand;
import com.example.im.message.domain.SendMessageResult;
import com.example.im.routing.ConversationRouteResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public final class SendMessageHandler {
    private static final long GENERATION_MILLIS = Duration.ofHours(6).toMillis();

    private final ConversationRouteResolver routeResolver;
    private final MessageTransactionPort transactionPort;
    private final CanonicalMessageHasher hasher;
    private final Clock clock;
    private final Duration retryWindow;
    private final Duration maxClockSkew;

    @Autowired
    public SendMessageHandler(
            ConversationRouteResolver routeResolver,
            MessageTransactionPort transactionPort,
            CanonicalMessageHasher hasher,
            ImProperties properties
    ) {
        this(routeResolver, transactionPort, hasher, Clock.systemUTC(),
                properties.message().automaticRetryWindow(), properties.message().maxClockSkew());
    }

    SendMessageHandler(
            ConversationRouteResolver routeResolver,
            MessageTransactionPort transactionPort,
            CanonicalMessageHasher hasher,
            Clock clock,
            Duration retryWindow,
            Duration maxClockSkew
    ) {
        this.routeResolver = routeResolver;
        this.transactionPort = transactionPort;
        this.hasher = hasher;
        this.clock = clock;
        this.retryWindow = retryWindow;
        this.maxClockSkew = maxClockSkew;
    }

    public SendMessageResult handle(SendMessageCommand command) {
        Instant now = clock.instant();
        Instant clientTime = uuidV7Time(command.clientMessageId());
        if (clientTime.isBefore(now.minus(retryWindow))) {
            throw new MessageRejectedException("CLIENT_MESSAGE_EXPIRED",
                    "clientMsgId is outside the automatic retry window", false);
        }
        if (clientTime.isAfter(now.plus(maxClockSkew))) {
            throw new MessageRejectedException("CLIENT_CLOCK_AHEAD",
                    "clientMsgId timestamp is too far in the future", false);
        }

        long generation = clientTime.toEpochMilli() / GENERATION_MILLIS;
        return transactionPort.execute(
                routeResolver.resolve(command.conversationId()),
                command,
                hasher.hash(command),
                generation,
                now
        );
    }

    static Instant uuidV7Time(java.util.UUID value) {
        if (value.version() != 7) {
            throw new MessageRejectedException("INVALID_CLIENT_MESSAGE_ID",
                    "clientMsgId must be a UUIDv7", false);
        }
        long unixMillis = (value.getMostSignificantBits() >>> 16) & 0xFFFFFFFFFFFFL;
        return Instant.ofEpochMilli(unixMillis);
    }
}

