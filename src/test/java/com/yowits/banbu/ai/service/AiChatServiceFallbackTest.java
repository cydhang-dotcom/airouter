package com.yowits.banbu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.router.ModelRouter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AiChatServiceFallbackTest {

    @Test
    void nonStreaming_fallbacks_to_second_route_when_first_fails() {
        // Routing: primary a1:m1, fallback a2:m2
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2")));
        ModelRouter router = new ModelRouter(rp);

        // Mock ChatClients and builder chain
        ChatClient client1 = Mockito.mock(ChatClient.class);
        ChatClient client2 = Mockito.mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec b1 = Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec b2 = Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        when(client1.prompt()).thenReturn(b1);
        when(client2.prompt()).thenReturn(b2);
        when(b1.options(any())).thenReturn(b1);
        when(b1.system(Mockito.<java.util.function.Consumer<ChatClient.PromptSystemSpec>>any())).thenReturn(b1);
        when(b1.user(Mockito.<java.util.function.Consumer<ChatClient.PromptUserSpec>>any())).thenReturn(b1);
        when(b2.options(any())).thenReturn(b2);
        when(b2.system(Mockito.<java.util.function.Consumer<ChatClient.PromptSystemSpec>>any())).thenReturn(b2);
        when(b2.user(Mockito.<java.util.function.Consumer<ChatClient.PromptUserSpec>>any())).thenReturn(b2);

        // First route throws on call()
        ChatClient.CallResponseSpec call1 = Mockito.mock(ChatClient.CallResponseSpec.class);
        when(b1.call()).thenReturn(call1);
        when(call1.chatResponse()).thenThrow(new RuntimeException("fail-first"));

        // Second route returns a ChatResponse
        ChatClient.CallResponseSpec call2 = Mockito.mock(ChatClient.CallResponseSpec.class);
        when(b2.call()).thenReturn(call2);
        ChatResponse ok = new ChatResponse(java.util.List.of());
        when(call2.chatResponse()).thenReturn(ok);

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client1, "a2", client2),
                Map.of("a1", "openai-compat", "a2", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = CompletableFuture.supplyAsync(() -> svc.chat(req).join()).join();
        assertThat(res).isNotNull();
        Mockito.verify(call2, Mockito.times(1)).chatResponse();
    }
}
