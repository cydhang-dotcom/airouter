package com.yowits.banbu.ai.api.dto;

public class ChatResponsePayload {
    private Object data; // text or JSON tree
    private String model;
    private String provider;

    public ChatResponsePayload() {}

    public ChatResponsePayload(Object data, String model, String provider) {
        this.data = data;
        this.model = model;
        this.provider = provider;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
