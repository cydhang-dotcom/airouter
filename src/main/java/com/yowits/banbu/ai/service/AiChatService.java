package com.yowits.banbu.ai.service;

import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.router.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final ProviderRegistry registry;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final AiPolicyProperties policyProps;

    public AiChatService(ProviderRegistry registry, ModelRouter modelRouter, ObjectMapper objectMapper,
                         AiPolicyProperties policyProps) {
        this.registry = registry;
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.policyProps = policyProps;
    }

    @Retry(name = "ai")
    @TimeLimiter(name = "ai")
    @CircuitBreaker(name = "ai")
    public CompletableFuture<ChatResponse> chat(ChatRequest req) {
        long start = System.currentTimeMillis();
        var chain = modelRouter.chooseChain(req.getScene());

        var policy = policyProps != null ? policyProps.resolve(req.getTenantId(), req.getScene()) : null;
        CompletableFuture<ChatResponse> fut = CompletableFuture.supplyAsync(() -> {
            RuntimeException lastEx = null;
            for (var route : chain) {
                try {
                    String model = route.model();
                    ChatClient client = selectClient(route.alias());
                    var builder = client.prompt();
                    var opts = buildOptions(route.alias(), model, req);
                    if (opts != null) builder = builder.options(opts);
                    if ("json".equalsIgnoreCase(req.getResponseFormat()) && req.getResponseSchema() != null && !req.getResponseSchema().isEmpty()) {
                        builder.system("请严格按以下JSON Schema返回结果，不要包含多余文字：" + escapePromptTemplate(objectToString(req.getResponseSchema())));
                    }
                    for (ChatMessage m : req.getMessages()) {
                        String role = m.getRole().toLowerCase();
                        if ("system".equals(role)) builder.system(m.getContent());
                        else builder.user(m.getContent());
                    }
                    int attempts = policy != null ? Math.max(1, policy.getPerRouteMaxAttempts()) : 1;
                    ChatResponse result = null;
                    for (int i = 0; i < attempts; i++) {
                        result = builder.call().chatResponse();
                        if (result != null) break;
                    }
                    auditLog(req, model, start, result);
                    return result;
                } catch (RuntimeException ex) {
                    lastEx = ex;
                    log.warn("route_failed alias={} scene={} message={}", route.alias(), req.getScene(), ex.getMessage());
                    if (policy != null && !policy.isAllowFallback()) {
                        break;
                    }
                }
            }
            throw lastEx != null ? lastEx : new IllegalStateException("no route available");
        });
        if (policy != null && policy.getTimeoutMs() > 0) {
            return fut.orTimeout(policy.getTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return fut;
    }

    @Retry(name = "ai")
    @TimeLimiter(name = "ai")
    @CircuitBreaker(name = "ai")
    public Flux<String> chatStream(ChatRequest req) {
        long start = System.currentTimeMillis();
        var route = modelRouter.choose(req.getScene());
        String model = route.model();
        ChatClient client = selectClient(route.alias());
        var builder = client.prompt();
        var opts = buildOptions(route.alias(), model, req);
        if (opts != null) builder = builder.options(opts);
        if ("json".equalsIgnoreCase(req.getResponseFormat()) && req.getResponseSchema() != null && !req.getResponseSchema().isEmpty()) {
            builder.system("请严格按以下JSON Schema返回结果，不要包含多余文字：" + escapePromptTemplate(objectToString(req.getResponseSchema())));
        }
        for (ChatMessage m : req.getMessages()) {
            String role = m.getRole().toLowerCase();
            if ("system".equals(role)) builder.system(m.getContent());
            else builder.user(m.getContent());
        }
        Flux<String> flux = builder.stream().content();
        long timeoutMs = policyProps != null ? policyProps.resolve(req.getTenantId(), req.getScene()).getTimeoutMs() : 60000;
        return flux.doOnComplete(() -> auditLog(req, model, start, null))
                   .timeout(Duration.ofMillis(Math.max(1, timeoutMs)));
    }

    private void auditLog(ChatRequest req, String model, long start, ChatResponse response) {
        long cost = System.currentTimeMillis() - start;
        String provider = providerFromModel(model);
        // If Spring AI exposes token usage in metadata, extract here; else log n/a
        log.info("ai_call tenant={} user={} scene={} provider={} model={} costMs={} status={}",
                req.getTenantId(), req.getUserId(), req.getScene(), provider, model, cost,
                response == null ? "STREAM" : "OK");
    }

    private String objectToString(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    private String escapePromptTemplate(String s) {
        return s.replace("{", "\\{").replace("}", "\\}");
    }

    private ChatClient selectClient(String alias) {
        ChatClient client = registry != null ? registry.get(alias) : null;
        if (client == null && registry != null && registry.all().size() == 1) {
            return registry.all().values().iterator().next();
        }
        if (client == null) {
            throw new IllegalStateException("No ChatClient for alias=" + alias);
        }
        return client;
    }

    private String providerFromModel(String model) {
        // Simple heuristic, can be replaced by explicit mapping
        String m = model.toLowerCase();
        if (m.contains("gpt") || m.contains("o1") || m.contains("openai")) return "openai";
        if (m.contains("claude")) return "anthropic";
        if (m.contains("gemini")) return "google";
        return "unknown";
    }

    private org.springframework.ai.chat.prompt.ChatOptions buildOptions(String alias, String model, ChatRequest req) {
        String type = registry != null ? registry.typeOf(alias) : null;
        if (type == null || type.equals("openai-compat")) {
            var b = OpenAiChatOptions.builder().withModel(model);
            if (req.getOptions() != null) {
                Object temp = req.getOptions().get("temperature");
                if (temp instanceof Number n) b = b.withTemperature(n.floatValue());
                Object topP = req.getOptions().get("topP");
                if (topP instanceof Number n) b = b.withTopP(n.floatValue());
                Object maxTokens = req.getOptions().get("maxTokens");
                if (maxTokens instanceof Number n) b = b.withMaxTokens(n.intValue());
            }
            return b.build();
        }
        // dashscope 或其他：返回 null 使用默认
        return null;
    }

    
}
