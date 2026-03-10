package com.yowits.banbu.ai.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class AiGatewayClient {
    private final RestClient rest; private final WebClient web;
    public AiGatewayClient(RestClient.Builder r, WebClient.Builder w) {
        this.rest = r.baseUrl("http://ai-service").build();
        this.web = w.baseUrl("http://ai-service").build();
    }
    public ChatResp chat(ChatReq req){
        return rest.post().uri("/ai/chat").contentType(MediaType.APPLICATION_JSON)
                .body(req).retrieve().body(ChatResp.class);
    }
    public Flux<ServerSentEvent<String>> chatStream(ChatReq req){
        return web.post().uri("/ai/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(req)
                .retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>(){});
    }
    public record ChatMessage(String role, String content) {}
    public record ChatReq(String scene, String tenantId, String userId, List<ChatMessage> messages, String responseFormat, boolean stream) {}
    public static class ChatResp { public Object data; public String model; }
}

