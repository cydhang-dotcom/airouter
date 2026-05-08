package com.yowits.banbu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.router.ModelRouter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
        CompletableFuture<ChatResponse> fut;
        if (policy != null && policy.isFastestWins() && chain.size() > 1) {
            fut = chatFastestWinsAsync(chain, req, policy, start);
        } else {
            fut = CompletableFuture.supplyAsync(() -> chatSequential(chain, req, policy, start));
        }
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
        var builder = preparePrompt(client, route.alias(), model, req);
        Flux<String> flux = builder.stream().content();
        long timeoutMs = policyProps != null ? policyProps.resolve(req.getTenantId(), req.getScene()).getTimeoutMs() : 60000;
        return flux.doOnComplete(() -> auditLog(req, model, start, null))
                .timeout(Duration.ofMillis(Math.max(1, timeoutMs)));
    }

    private ChatResponse chatSequential(List<ModelRouter.Route> chain, ChatRequest req, AiPolicyProperties.Policy policy, long start) {
        RuntimeException lastEx = null;
        for (var route : chain) {
            try {
                return callRoute(route, req, policy, start);
            } catch (RuntimeException ex) {
                lastEx = ex;
                log.warn("route_failed alias={} scene={} message={}", route.alias(), req.getScene(), ex.getMessage());
                if (policy != null && !policy.isAllowFallback()) {
                    break;
                }
            }
        }
        throw wrapTerminalException(lastEx != null ? lastEx : new IllegalStateException("no route available"));
    }

    private CompletableFuture<ChatResponse> chatFastestWinsAsync(List<ModelRouter.Route> chain, ChatRequest req,
                                                                 AiPolicyProperties.Policy policy, long start) {
        int candidateCount = Math.min(Math.max(1, policy.getRaceMaxCandidates()), chain.size());
        List<ModelRouter.Route> raceCandidates = new ArrayList<>(chain.subList(0, candidateCount));
        List<ModelRouter.Route> fallbackCandidates = candidateCount < chain.size()
                ? new ArrayList<>(chain.subList(candidateCount, chain.size()))
                : List.of();

        CompletableFuture<ChatResponse> winner = firstSuccessful(raceCandidates, req, policy, start);
        return winner.handle((response, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(response);
            }
            if (!fallbackCandidates.isEmpty() && policy.isAllowFallback()) {
                return CompletableFuture.supplyAsync(() -> chatSequential(fallbackCandidates, req, policy, start));
            }
            return CompletableFuture.<ChatResponse>failedFuture(
                    wrapTerminalException(unwrapRuntimeException(throwable))
            );
        }).thenCompose(future -> future);
    }

    private CompletableFuture<ChatResponse> firstSuccessful(List<ModelRouter.Route> routes, ChatRequest req,
                                                            AiPolicyProperties.Policy policy, long start) {
        CompletableFuture<ChatResponse> winner = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(routes.size());
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<CompletableFuture<ChatResponse>> tasks = new ArrayList<>();

        for (var route : routes) {
            CompletableFuture<ChatResponse> task =
                    CompletableFuture.supplyAsync(() -> callRoute(route, req, policy, start));
            tasks.add(task);
            task.whenComplete((response, throwable) -> {
                if (throwable == null && response != null) {
                    if (winner.complete(response)) {
                        log.info("route_race_winner scene={} alias={} model={} candidates={}",
                                req.getScene(), route.alias(), route.model(), routes.size());
                        tasks.forEach(other -> {
                            if (other != task) {
                                other.cancel(true);
                            }
                        });
                    }
                    remaining.decrementAndGet();
                    return;
                }

                Throwable cause = throwable instanceof CompletionException completion && completion.getCause() != null
                        ? completion.getCause()
                        : throwable;
                if (cause instanceof CancellationException && winner.isDone()) {
                    return;
                }
                failures.add(cause);
                log.warn("route_failed alias={} scene={} message={}",
                        route.alias(), req.getScene(), cause == null ? "unknown" : cause.getMessage());
                if (remaining.decrementAndGet() == 0 && !winner.isDone()) {
                    Throwable last = failures.isEmpty() ? new IllegalStateException("no route available")
                            : failures.get(failures.size() - 1);
                    winner.completeExceptionally(last);
                }
            });
        }
        return winner;
    }

    private ChatResponse callRoute(ModelRouter.Route route, ChatRequest req, AiPolicyProperties.Policy policy, long start) {
        String model = route.model();
        ChatClient client = selectClient(route.alias());
        int attempts = policy != null ? Math.max(1, policy.getPerRouteMaxAttempts()) : 1;
        RuntimeException lastRouteException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                var builder = preparePrompt(client, route.alias(), model, req);
                ChatResponse result = builder.call().chatResponse();
                if (result != null) {
                    auditLog(req, model, start, result);
                    return result;
                }
                lastRouteException = new IllegalStateException("empty response from provider");
            } catch (RuntimeException ex) {
                Throwable classifiedEx = AiErrorClassifier.classify(ex);
                boolean canRetry = AiErrorClassifier.isRetryable(classifiedEx);
                boolean hasNextAttempt = attempt < attempts;

                log.warn("route_failed alias={} scene={} attempt={}/{} retryable={} message={}",
                        route.alias(), req.getScene(), attempt, attempts, canRetry, ex.getMessage());

                if (!canRetry) {
                    if (classifiedEx instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException(classifiedEx.getMessage(), classifiedEx);
                }

                lastRouteException = classifiedEx instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(classifiedEx.getMessage(), classifiedEx);

                if (hasNextAttempt) {
                    sleepBeforeRetry(attempt);
                }
            }
        }

        if (lastRouteException != null) {
            throw lastRouteException;
        }
        throw new IllegalStateException("no route available");
    }

    private RuntimeException unwrapRuntimeException(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException completionException && completionException.getCause() != null
                ? completionException.getCause()
                : throwable;
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(cause.getMessage(), cause);
    }

    private void auditLog(ChatRequest req, String model, long start, ChatResponse response) {
        long cost = System.currentTimeMillis() - start;
        String provider = providerFromModel(model);
        Usage usage = response != null && response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        log.info("ai_call tenant={} user={} scene={} provider={} model={} costMs={} promptTokens={} generationTokens={} totalTokens={} status={}",
                req.getTenantId(), req.getUserId(), req.getScene(), provider, model, cost,
                tokenValue(usage == null ? null : usage.getPromptTokens()),
                tokenValue(usage == null ? null : usage.getGenerationTokens()),
                tokenValue(usage == null ? null : usage.getTotalTokens()),
                response == null ? "STREAM" : "OK");
    }

    private String tokenValue(Long value) {
        return value == null ? "n/a" : String.valueOf(value);
    }

    private String objectToString(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
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
                Object maxTokens = req.getOptions().get("maxTokens");
                if (maxTokens instanceof Number n) b = b.withMaxTokens(n.intValue());
                if (!isKimi25Model(model)) {
                    Object temp = req.getOptions().get("temperature");
                    if (temp instanceof Number n) b = b.withTemperature(n.floatValue());
                    Object topP = req.getOptions().get("topP");
                    if (topP instanceof Number n) b = b.withTopP(n.floatValue());
                }
            }
            return b.build();
        }
        return null;
    }

    private boolean isKimi25Model(String model) {
        return model != null && model.toLowerCase().contains("kimi-k2.5");
    }

    private ChatClient.ChatClientRequestSpec preparePrompt(ChatClient client, String alias, String model, ChatRequest req) {
        var builder = client.prompt();
        var opts = buildOptions(alias, model, req);
        if (opts != null) builder = builder.options(opts);
        if ("json".equalsIgnoreCase(req.getResponseFormat()) && req.getResponseSchema() != null && !req.getResponseSchema().isEmpty()) {
            builder.system("请严格按以下JSON Schema返回结果，不要包含多余文字：" + escapePromptTemplate(objectToString(req.getResponseSchema())));
        }
        for (ChatMessage m : req.getMessages()) {
            String role = m.getRole().toLowerCase();
            if ("system".equals(role)) builder.system(m.getContent());
            else builder.user(m.getContent());
        }
        return builder;
    }

    private void sleepBeforeRetry(int attempt) {
        long waitMs = Math.min(1000L, 200L * attempt);
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private RuntimeException wrapTerminalException(RuntimeException ex) {
        if (AiErrorClassifier.isEngineOverloaded(ex)) {
            return new UpstreamServiceException(503, "AI_PROVIDER_OVERLOADED",
                    "Upstream AI provider is overloaded, please retry later", ex);
        }
        if (AiErrorClassifier.isTimeoutRelated(ex)) {
            return new UpstreamServiceException(504, "AI_UPSTREAM_TIMEOUT",
                    "Upstream AI provider request timed out", ex);
        }
        if (AiErrorClassifier.isNetworkRelated(ex)) {
            return new UpstreamServiceException(502, "AI_UPSTREAM_NETWORK_ERROR",
                    "Network error while connecting to upstream AI provider", ex);
        }
        Integer statusCode = AiErrorClassifier.statusCodeOf(ex);
        if (statusCode != null && statusCode == 429) {
            return new UpstreamServiceException(429, "AI_UPSTREAM_RATE_LIMITED",
                    "Upstream AI provider rate limited the request", ex);
        }
        return ex;
    }
}
