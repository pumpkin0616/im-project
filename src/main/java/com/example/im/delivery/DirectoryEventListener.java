package com.example.im.delivery;

import com.example.im.config.ImProperties;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class DirectoryEventListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(DirectoryEventListener.class);
    private final ObjectMapper objectMapper;
    private final ConversationRouteResolver routes;
    private final JdbcTemplate jdbc;
    private final RealtimeDeliveryRouter router;
    private final String localShard;

    public DirectoryEventListener(ObjectMapper objectMapper, ConversationRouteResolver routes,
                                  JdbcTemplate jdbc, RealtimeDeliveryRouter router,
                                  ImProperties properties) {
        this.objectMapper=objectMapper; this.routes=routes; this.jdbc=jdbc; this.router=router;
        this.localShard=properties.routing().localShardId();
    }

    @Override
    public ConsumeResult consume(MessageView message) {
        try {
            ByteBuffer body=message.getBody().duplicate(); byte[] bytes=new byte[body.remaining()]; body.get(bytes);
            JsonNode envelope=objectMapper.readTree(bytes);
            UUID conversationId=UUID.fromString(envelope.path("conversationId").asString());
            String eventType=clientEvent(envelope.path("eventType").asString(),envelope.path("payload"));
            if(eventType==null)return ConsumeResult.SUCCESS;
            ResolvedRoute route=routes.resolve(conversationId);
            if(!localShard.equals(route.shardId()))throw new IllegalStateException("Directory event routed to wrong shard");
            String members=new MessageTableNames(route.tableGroup(),0).members();
            List<UUID> recipients=jdbc.query("SELECT user_id FROM "+members+" WHERE conversation_id=?",
                    (rs,n)->UuidCodec.fromBytes(rs.getBytes(1)),UuidCodec.toBytes(conversationId));
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("eventType",eventType); data.put("conversationId",conversationId.toString());
            data.put("occurredAt",Instant.now().toString());
            router.dispatch(recipients,"DIRECTORY_EVENT",data);
            return ConsumeResult.SUCCESS;
        } catch(Exception exception) {
            log.warn("Directory event delivery failed for {}: {}",message.getMessageId(),exception.getMessage());
            return ConsumeResult.FAILURE;
        }
    }

    private String clientEvent(String type,JsonNode payload) {
        return switch(type) {
            case "CONVERSATION_CREATED" -> "CONVERSATION_ADDED";
            case "CONVERSATION_UPDATED" -> "CONVERSATION_UPDATED";
            case "CONVERSATION_DISSOLVED" -> "CONVERSATION_DISSOLVED";
            case "MEMBERSHIP_CHANGED" -> switch(payload.path("operation").asString()) {
                case "ADD" -> "MEMBER_ADDED"; case "REMOVE" -> "MEMBER_REMOVED";
                case "LEAVE" -> "MEMBER_LEFT"; case "ROLE" -> "ROLE_CHANGED"; default -> null;
            };
            default -> null;
        };
    }
}
