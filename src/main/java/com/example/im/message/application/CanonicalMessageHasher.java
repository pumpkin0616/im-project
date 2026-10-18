package com.example.im.message.application;

import com.example.im.message.domain.SendMessageCommand;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public final class CanonicalMessageHasher {
  private final ObjectMapper objectMapper;

  public CanonicalMessageHasher(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public byte[] hash(SendMessageCommand command) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("conversationId", command.conversationId().toString());
    root.put("type", command.type().name());
    root.set("content", canonicalize(command.content()));
    ArrayNode attachments = root.putArray("attachmentIds");
    command.attachmentIds().stream()
        .map(Object::toString)
        .sorted()
        .forEach(attachments::add);
    try {
      return sha256(objectMapper.writeValueAsString(root));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Message content cannot be canonicalized", exception);
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }
    if (node.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      node.forEach(child -> result.add(canonicalize(child)));
      return result;
    }
    List<String> names = new ArrayList<>(node.propertyNames());
    names.sort(Comparator.naturalOrder());
    ObjectNode result = objectMapper.createObjectNode();
    names.forEach(name -> result.set(name, canonicalize(node.get(name))));
    return result;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
