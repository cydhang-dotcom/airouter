package com.yowits.banbu.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.api.dto.ChatResponsePayload;
import com.yowits.banbu.ai.service.AiChatService;
import com.yowits.banbu.ai.service.InvalidChatRequestException;
import com.yowits.banbu.ai.service.TenantGuardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("/ai")
@Tag(name = "AI Gateway")
public class ChatController {

    private final AiChatService chatService;
    private final ObjectMapper objectMapper;
    private final TenantGuardService tenantGuardService;

    public ChatController(AiChatService chatService, ObjectMapper objectMapper, TenantGuardService tenantGuardService) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.tenantGuardService = tenantGuardService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "非流式聊天：支持结构化JSON输出、场景路由与降级")
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest req) {
        if (req.isStream()) {
            throw new InvalidChatRequestException("Use /ai/chat/stream for streaming");
        }
        tenantGuardService.checkTenant(req.getTenantId());
        ChatResponse response = chatService.chat(req).join();
        Object payload = response.getResult().getOutput().getContent();
        String model = response.getMetadata().getModel();
        if ("json".equalsIgnoreCase(req.getResponseFormat()) && payload instanceof String s) {
            JsonNode json = tryParseJson(s);
            if (json != null) {
                return ResponseEntity.ok(new ChatResponsePayload(json, model, ""));
            }
        }
        if (payload instanceof JsonNode json) {
            return ResponseEntity.ok(new ChatResponsePayload(json, model, ""));
        }
        return ResponseEntity.ok(new ChatResponsePayload(String.valueOf(payload), model, ""));
    }

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE 流式聊天：按场景首选路由输出")
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatRequest req) {
        tenantGuardService.checkTenant(req.getTenantId());
        Flux<String> flux = chatService.chatStream(req);
        return flux.map(token -> ServerSentEvent.builder(token).event("message").build())
                   .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").event("done").build()))
                   .timeout(Duration.ofSeconds(120));
    }

    private JsonNode tryParseJson(String raw) {
        try {
            String s = stripFence(raw);
            return objectMapper.readTree(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstBrace = t.indexOf('{');
            int firstBracket = t.indexOf('[');
            int idx = -1;
            if (firstBrace >= 0 && firstBracket >= 0) idx = Math.min(firstBrace, firstBracket);
            else idx = Math.max(firstBrace, firstBracket);
            if (idx > 0) return t.substring(idx).replaceAll("```$"," ").trim();
        }
        return t;
    }
}
