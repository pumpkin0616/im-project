package com.example.im.message.application;

import com.example.im.message.domain.SendMessageCommand;
import com.example.im.message.domain.SendMessageResult;
import com.example.im.routing.ResolvedRoute;

import java.time.Instant;

public interface MessageTransactionPort {
    SendMessageResult execute(
            ResolvedRoute route,
            SendMessageCommand command,
            byte[] requestHash,
            long dedupGeneration,
            Instant requestTime
    );
}

