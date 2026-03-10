package com.yowits.banbu.ai.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public class ChatRequest {

    @NotBlank
    private String scene; // scene code, e.g., CONTRACT_SUMMARY

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotEmpty
    @Valid
    private List<ChatMessage> messages;

    private boolean stream = false;

    /** json | text; default text */
    private String responseFormat = "text";

    /** provider-specific options (temperature, topP, etc.) */
    private Map<String, Object> options;

    /** optional JSON schema hint for structured output */
    private Map<String, Object> responseSchema;

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public Map<String, Object> getResponseSchema() { return responseSchema; }
    public void setResponseSchema(Map<String, Object> responseSchema) { this.responseSchema = responseSchema; }
}
