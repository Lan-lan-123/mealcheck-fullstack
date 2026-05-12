package com.example.mealcheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mealcheck")
public class AppProperties {
    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private String uploadDir = "./uploads";
    private Ai ai = new Ai();
    private Knowledge knowledge = new Knowledge();

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }
    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }
    public Knowledge getKnowledge() { return knowledge; }
    public void setKnowledge(Knowledge knowledge) { this.knowledge = knowledge; }

    public static class Jwt {
        private String secret;
        private long expirationMinutes = 10080;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpirationMinutes() { return expirationMinutes; }
        public void setExpirationMinutes(long expirationMinutes) { this.expirationMinutes = expirationMinutes; }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class Ai {
        private String apiKey = "";
        private String baseUrl;
        private String model;
        private int timeoutSeconds = 60;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Knowledge {
        private boolean autoIndex = true;
        private int embeddingDim = 384;
        public boolean isAutoIndex() { return autoIndex; }
        public void setAutoIndex(boolean autoIndex) { this.autoIndex = autoIndex; }
        public int getEmbeddingDim() { return embeddingDim; }
        public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }
    }
}
