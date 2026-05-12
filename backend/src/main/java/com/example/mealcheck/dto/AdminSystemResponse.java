package com.example.mealcheck.dto;

public class AdminSystemResponse {

    private String backend;
    private String database;
    private String vectorDatabase;
    private String embeddingMethod;
    private int embeddingDimension;
    private String aiModel;
    private boolean apiKeyConfigured;
    private boolean baseUrlConfigured;
    private String ragKnowledgeFile;

    public AdminSystemResponse() {
    }

    public AdminSystemResponse(String backend,
                               String database,
                               String vectorDatabase,
                               String embeddingMethod,
                               int embeddingDimension,
                               String aiModel,
                               boolean apiKeyConfigured,
                               boolean baseUrlConfigured,
                               String ragKnowledgeFile) {
        this.backend = backend;
        this.database = database;
        this.vectorDatabase = vectorDatabase;
        this.embeddingMethod = embeddingMethod;
        this.embeddingDimension = embeddingDimension;
        this.aiModel = aiModel;
        this.apiKeyConfigured = apiKeyConfigured;
        this.baseUrlConfigured = baseUrlConfigured;
        this.ragKnowledgeFile = ragKnowledgeFile;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getVectorDatabase() {
        return vectorDatabase;
    }

    public void setVectorDatabase(String vectorDatabase) {
        this.vectorDatabase = vectorDatabase;
    }

    public String getEmbeddingMethod() {
        return embeddingMethod;
    }

    public void setEmbeddingMethod(String embeddingMethod) {
        this.embeddingMethod = embeddingMethod;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public boolean isApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public void setApiKeyConfigured(boolean apiKeyConfigured) {
        this.apiKeyConfigured = apiKeyConfigured;
    }

    public boolean isBaseUrlConfigured() {
        return baseUrlConfigured;
    }

    public void setBaseUrlConfigured(boolean baseUrlConfigured) {
        this.baseUrlConfigured = baseUrlConfigured;
    }

    public String getRagKnowledgeFile() {
        return ragKnowledgeFile;
    }

    public void setRagKnowledgeFile(String ragKnowledgeFile) {
        this.ragKnowledgeFile = ragKnowledgeFile;
    }
}