package com.example.mealcheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mealcheck")
public class AppProperties {
    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private String uploadDir = "./uploads";
    private Upload upload = new Upload();
    private Ai ai = new Ai();
    private Knowledge knowledge = new Knowledge();
    private UploadGuard uploadGuard = new UploadGuard();
    private RateLimit rateLimit = new RateLimit();
    private Mcp mcp = new Mcp();
    private AssistantFunctionCalling assistantFunctionCalling = new AssistantFunctionCalling();
    private AssistantMemory assistantMemory = new AssistantMemory();
    private AssistantTools assistantTools = new AssistantTools();
    private WeeklyReport weeklyReport = new WeeklyReport();
    private RemoteResilience remoteResilience = new RemoteResilience();

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }
    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
    public Upload getUpload() { return upload; }
    public void setUpload(Upload upload) { this.upload = upload; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }
    public Knowledge getKnowledge() { return knowledge; }
    public void setKnowledge(Knowledge knowledge) { this.knowledge = knowledge; }
    public UploadGuard getUploadGuard() { return uploadGuard; }
    public void setUploadGuard(UploadGuard uploadGuard) { this.uploadGuard = uploadGuard; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp mcp) { this.mcp = mcp; }
    public AssistantFunctionCalling getAssistantFunctionCalling() { return assistantFunctionCalling; }
    public void setAssistantFunctionCalling(AssistantFunctionCalling assistantFunctionCalling) {
        this.assistantFunctionCalling = assistantFunctionCalling;
    }
    public AssistantMemory getAssistantMemory() { return assistantMemory; }
    public void setAssistantMemory(AssistantMemory assistantMemory) { this.assistantMemory = assistantMemory; }
    public AssistantTools getAssistantTools() { return assistantTools; }
    public void setAssistantTools(AssistantTools assistantTools) { this.assistantTools = assistantTools; }
    public WeeklyReport getWeeklyReport() { return weeklyReport; }
    public void setWeeklyReport(WeeklyReport weeklyReport) { this.weeklyReport = weeklyReport; }
    public RemoteResilience getRemoteResilience() { return remoteResilience; }
    public void setRemoteResilience(RemoteResilience remoteResilience) { this.remoteResilience = remoteResilience; }

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
        private int embeddingDim = 512;
        private String embeddingApiKey = "";
        private String embeddingBaseUrl = "";
        private String embeddingModel = "";
        private int embeddingTimeoutSeconds = 30;
        private double vectorWeight = 0.65;
        private double keywordWeight = 0.25;
        private double categoryWeight = 0.10;
        private int rerankCandidateMultiplier = 4;
        private int rrfK = 60;
        private boolean rerankEnabled = false;
        private String rerankUrl = "";
        private String rerankApiKey = "";
        private String rerankModel = "";
        private int rerankTimeoutSeconds = 10;
        private double rerankModelWeight = 0.75;
        private double rerankerMinScore = 0.20;
        private boolean resultFilterEnabled = true;
        private double resultMinScore = 0.35;
        private double resultRelativeScore = 0.60;
        private double resultDedupSimilarity = 0.88;
        private int resultCandidateMultiplier = 4;
        private int chunkMinTokens = 300;
        private int chunkMaxTokens = 500;
        private int chunkOverlapTokens = 50;
        private String splitterStrategy = "recursive-markdown";
        private String sourceVersion = "v2-recursive";
        private String vectorStorage = "halfvec";
        private double benchmarkRegressionTolerance = 0.02;
        public boolean isAutoIndex() { return autoIndex; }
        public void setAutoIndex(boolean autoIndex) { this.autoIndex = autoIndex; }
        public int getEmbeddingDim() { return embeddingDim; }
        public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }
        public String getEmbeddingApiKey() { return embeddingApiKey; }
        public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }
        public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
        public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingTimeoutSeconds() { return embeddingTimeoutSeconds; }
        public void setEmbeddingTimeoutSeconds(int embeddingTimeoutSeconds) { this.embeddingTimeoutSeconds = embeddingTimeoutSeconds; }
        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
        public double getKeywordWeight() { return keywordWeight; }
        public void setKeywordWeight(double keywordWeight) { this.keywordWeight = keywordWeight; }
        public double getCategoryWeight() { return categoryWeight; }
        public void setCategoryWeight(double categoryWeight) { this.categoryWeight = categoryWeight; }
        public int getRerankCandidateMultiplier() { return rerankCandidateMultiplier; }
        public void setRerankCandidateMultiplier(int rerankCandidateMultiplier) { this.rerankCandidateMultiplier = rerankCandidateMultiplier; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public boolean isRerankEnabled() { return rerankEnabled; }
        public void setRerankEnabled(boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }
        public String getRerankUrl() { return rerankUrl; }
        public void setRerankUrl(String rerankUrl) { this.rerankUrl = rerankUrl; }
        public String getRerankApiKey() { return rerankApiKey; }
        public void setRerankApiKey(String rerankApiKey) { this.rerankApiKey = rerankApiKey; }
        public String getRerankModel() { return rerankModel; }
        public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }
        public int getRerankTimeoutSeconds() { return rerankTimeoutSeconds; }
        public void setRerankTimeoutSeconds(int rerankTimeoutSeconds) { this.rerankTimeoutSeconds = rerankTimeoutSeconds; }
        public double getRerankModelWeight() { return rerankModelWeight; }
        public void setRerankModelWeight(double rerankModelWeight) { this.rerankModelWeight = rerankModelWeight; }
        public double getRerankerMinScore() { return rerankerMinScore; }
        public void setRerankerMinScore(double rerankerMinScore) { this.rerankerMinScore = rerankerMinScore; }
        public boolean isResultFilterEnabled() { return resultFilterEnabled; }
        public void setResultFilterEnabled(boolean resultFilterEnabled) { this.resultFilterEnabled = resultFilterEnabled; }
        public double getResultMinScore() { return resultMinScore; }
        public void setResultMinScore(double resultMinScore) { this.resultMinScore = resultMinScore; }
        public double getResultRelativeScore() { return resultRelativeScore; }
        public void setResultRelativeScore(double resultRelativeScore) { this.resultRelativeScore = resultRelativeScore; }
        public double getResultDedupSimilarity() { return resultDedupSimilarity; }
        public void setResultDedupSimilarity(double resultDedupSimilarity) { this.resultDedupSimilarity = resultDedupSimilarity; }
        public int getResultCandidateMultiplier() { return resultCandidateMultiplier; }
        public void setResultCandidateMultiplier(int resultCandidateMultiplier) { this.resultCandidateMultiplier = resultCandidateMultiplier; }
        public int getChunkMinTokens() { return chunkMinTokens; }
        public void setChunkMinTokens(int chunkMinTokens) { this.chunkMinTokens = chunkMinTokens; }
        public int getChunkMaxTokens() { return chunkMaxTokens; }
        public void setChunkMaxTokens(int chunkMaxTokens) { this.chunkMaxTokens = chunkMaxTokens; }
        public int getChunkOverlapTokens() { return chunkOverlapTokens; }
        public void setChunkOverlapTokens(int chunkOverlapTokens) { this.chunkOverlapTokens = chunkOverlapTokens; }
        public String getSplitterStrategy() { return splitterStrategy; }
        public void setSplitterStrategy(String splitterStrategy) { this.splitterStrategy = splitterStrategy; }
        public String getSourceVersion() { return sourceVersion; }
        public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
        public String getVectorStorage() { return vectorStorage; }
        public void setVectorStorage(String vectorStorage) { this.vectorStorage = vectorStorage; }
        public double getBenchmarkRegressionTolerance() { return benchmarkRegressionTolerance; }
        public void setBenchmarkRegressionTolerance(double benchmarkRegressionTolerance) { this.benchmarkRegressionTolerance = benchmarkRegressionTolerance; }
    }

    public static class UploadGuard {
        private int nonFoodWindowMinutes = 5;
        private int nonFoodLimit = 10;
        private int nonFoodBlockMinutes = 60;

        public int getNonFoodWindowMinutes() { return nonFoodWindowMinutes; }
        public void setNonFoodWindowMinutes(int nonFoodWindowMinutes) {
            this.nonFoodWindowMinutes = nonFoodWindowMinutes;
        }
        public int getNonFoodLimit() { return nonFoodLimit; }
        public void setNonFoodLimit(int nonFoodLimit) { this.nonFoodLimit = nonFoodLimit; }
        public int getNonFoodBlockMinutes() { return nonFoodBlockMinutes; }
        public void setNonFoodBlockMinutes(int nonFoodBlockMinutes) { this.nonFoodBlockMinutes = nonFoodBlockMinutes; }
    }

    public static class Upload {
        private long maxInputBytes = 10L * 1024 * 1024;
        private int maxWidth = 4096;
        private int maxHeight = 4096;
        private long maxPixels = 16_777_216L;
        private int normalizedMaxEdge = 2560;
        private float jpegQuality = 0.9f;
        private boolean orphanCleanupEnabled = true;
        private long orphanGraceHours = 24;

        public long getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(long maxInputBytes) { this.maxInputBytes = maxInputBytes; }
        public int getMaxWidth() { return maxWidth; }
        public void setMaxWidth(int maxWidth) { this.maxWidth = maxWidth; }
        public int getMaxHeight() { return maxHeight; }
        public void setMaxHeight(int maxHeight) { this.maxHeight = maxHeight; }
        public long getMaxPixels() { return maxPixels; }
        public void setMaxPixels(long maxPixels) { this.maxPixels = maxPixels; }
        public int getNormalizedMaxEdge() { return normalizedMaxEdge; }
        public void setNormalizedMaxEdge(int normalizedMaxEdge) { this.normalizedMaxEdge = normalizedMaxEdge; }
        public float getJpegQuality() { return jpegQuality; }
        public void setJpegQuality(float jpegQuality) { this.jpegQuality = jpegQuality; }
        public boolean isOrphanCleanupEnabled() { return orphanCleanupEnabled; }
        public void setOrphanCleanupEnabled(boolean orphanCleanupEnabled) { this.orphanCleanupEnabled = orphanCleanupEnabled; }
        public long getOrphanGraceHours() { return orphanGraceHours; }
        public void setOrphanGraceHours(long orphanGraceHours) { this.orphanGraceHours = orphanGraceHours; }
    }

    public static class RateLimit {
        private TokenBucket assistant = new TokenBucket(5, 1, 3, 1);
        private TokenBucket mealAnalysis = new TokenBucket(3, 1, 6, 1);

        public TokenBucket getAssistant() { return assistant; }
        public void setAssistant(TokenBucket assistant) { this.assistant = assistant; }
        public TokenBucket getMealAnalysis() { return mealAnalysis; }
        public void setMealAnalysis(TokenBucket mealAnalysis) { this.mealAnalysis = mealAnalysis; }
    }

    public static class TokenBucket {
        private long capacity;
        private long refillTokens;
        private long refillPeriodSeconds;
        private long requestCost;

        public TokenBucket() {
        }

        public TokenBucket(long capacity, long refillTokens, long refillPeriodSeconds, long requestCost) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillPeriodSeconds = refillPeriodSeconds;
            this.requestCost = requestCost;
        }

        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }
        public long getRefillTokens() { return refillTokens; }
        public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }
        public long getRefillPeriodSeconds() { return refillPeriodSeconds; }
        public void setRefillPeriodSeconds(long refillPeriodSeconds) { this.refillPeriodSeconds = refillPeriodSeconds; }
        public long getRequestCost() { return requestCost; }
        public void setRequestCost(long requestCost) { this.requestCost = requestCost; }
    }

    public static class Mcp {
        private boolean enabled = true;
        private String endpoint = "/mcp";
        private String serverName = "mealcheck-mcp-server";
        private String serverVersion = "1.0.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getServerName() { return serverName; }
        public void setServerName(String serverName) { this.serverName = serverName; }
        public String getServerVersion() { return serverVersion; }
        public void setServerVersion(String serverVersion) { this.serverVersion = serverVersion; }
    }

    public static class AssistantFunctionCalling {
        private boolean enabled = false;
        private int maxToolRounds = 3;
        private int maxToolCalls = 6;
        private int totalTimeoutSeconds = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxToolRounds() { return maxToolRounds; }
        public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }
        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        public int getTotalTimeoutSeconds() { return totalTimeoutSeconds; }
        public void setTotalTimeoutSeconds(int totalTimeoutSeconds) { this.totalTimeoutSeconds = totalTimeoutSeconds; }
    }

    public static class AssistantMemory {
        private int historyTokenBudget = 1200;
        private int recentMessages = 6;
        private int summaryMaxTokens = 500;
        private int relevantHistoryMessages = 4;
        private int recentDatabaseMessages = 40;
        private int summaryBatchMessages = 100;

        public int getHistoryTokenBudget() { return historyTokenBudget; }
        public void setHistoryTokenBudget(int historyTokenBudget) { this.historyTokenBudget = historyTokenBudget; }
        public int getRecentMessages() { return recentMessages; }
        public void setRecentMessages(int recentMessages) { this.recentMessages = recentMessages; }
        public int getSummaryMaxTokens() { return summaryMaxTokens; }
        public void setSummaryMaxTokens(int summaryMaxTokens) { this.summaryMaxTokens = summaryMaxTokens; }
        public int getRelevantHistoryMessages() { return relevantHistoryMessages; }
        public void setRelevantHistoryMessages(int relevantHistoryMessages) { this.relevantHistoryMessages = relevantHistoryMessages; }
        public int getRecentDatabaseMessages() { return recentDatabaseMessages; }
        public void setRecentDatabaseMessages(int recentDatabaseMessages) { this.recentDatabaseMessages = recentDatabaseMessages; }
        public int getSummaryBatchMessages() { return summaryBatchMessages; }
        public void setSummaryBatchMessages(int summaryBatchMessages) { this.summaryBatchMessages = summaryBatchMessages; }
    }

    public static class AssistantTools {
        private int httpPoolSize = 8;
        private int httpQueueCapacity = 16;
        private int dbPoolSize = 6;
        private int dbQueueCapacity = 16;
        private int ragPoolSize = 4;
        private int ragQueueCapacity = 8;
        private int backgroundPoolSize = 2;
        private int backgroundQueueCapacity = 16;
        private int timeoutMillis = 2500;
        private int totalTimeoutMillis = 3000;

        public int getHttpPoolSize() { return httpPoolSize; }
        public void setHttpPoolSize(int httpPoolSize) { this.httpPoolSize = httpPoolSize; }
        public int getHttpQueueCapacity() { return httpQueueCapacity; }
        public void setHttpQueueCapacity(int httpQueueCapacity) { this.httpQueueCapacity = httpQueueCapacity; }
        public int getDbPoolSize() { return dbPoolSize; }
        public void setDbPoolSize(int dbPoolSize) { this.dbPoolSize = dbPoolSize; }
        public int getDbQueueCapacity() { return dbQueueCapacity; }
        public void setDbQueueCapacity(int dbQueueCapacity) { this.dbQueueCapacity = dbQueueCapacity; }
        public int getRagPoolSize() { return ragPoolSize; }
        public void setRagPoolSize(int ragPoolSize) { this.ragPoolSize = ragPoolSize; }
        public int getRagQueueCapacity() { return ragQueueCapacity; }
        public void setRagQueueCapacity(int ragQueueCapacity) { this.ragQueueCapacity = ragQueueCapacity; }
        public int getBackgroundPoolSize() { return backgroundPoolSize; }
        public void setBackgroundPoolSize(int backgroundPoolSize) { this.backgroundPoolSize = backgroundPoolSize; }
        public int getBackgroundQueueCapacity() { return backgroundQueueCapacity; }
        public void setBackgroundQueueCapacity(int backgroundQueueCapacity) { this.backgroundQueueCapacity = backgroundQueueCapacity; }
        public int getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
        public int getTotalTimeoutMillis() { return totalTimeoutMillis; }
        public void setTotalTimeoutMillis(int totalTimeoutMillis) { this.totalTimeoutMillis = totalTimeoutMillis; }
    }

    public static class WeeklyReport {
        private int batchSize = 100;
        private int executorCorePoolSize = 2;
        private int executorMaxPoolSize = 4;
        private int executorQueueCapacity = 20;
        private int agentExecutorCorePoolSize = 2;
        private int agentExecutorMaxPoolSize = 4;
        private int agentExecutorQueueCapacity = 20;
        private int workerBatchSize = 4;
        private int maxAttempts = 3;
        private long staleLeaseSeconds = 600;
        private long workerDelayMillis = 5000;
        private boolean multiAgentEnabled = true;
        private int maxReviewRevisions = 1;
        private int ragLimit = 5;
        private int agentTimeoutSeconds = 30;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getExecutorCorePoolSize() { return executorCorePoolSize; }
        public void setExecutorCorePoolSize(int executorCorePoolSize) { this.executorCorePoolSize = executorCorePoolSize; }
        public int getExecutorMaxPoolSize() { return executorMaxPoolSize; }
        public void setExecutorMaxPoolSize(int executorMaxPoolSize) { this.executorMaxPoolSize = executorMaxPoolSize; }
        public int getExecutorQueueCapacity() { return executorQueueCapacity; }
        public void setExecutorQueueCapacity(int executorQueueCapacity) { this.executorQueueCapacity = executorQueueCapacity; }
        public int getAgentExecutorCorePoolSize() { return agentExecutorCorePoolSize; }
        public void setAgentExecutorCorePoolSize(int agentExecutorCorePoolSize) { this.agentExecutorCorePoolSize = agentExecutorCorePoolSize; }
        public int getAgentExecutorMaxPoolSize() { return agentExecutorMaxPoolSize; }
        public void setAgentExecutorMaxPoolSize(int agentExecutorMaxPoolSize) { this.agentExecutorMaxPoolSize = agentExecutorMaxPoolSize; }
        public int getAgentExecutorQueueCapacity() { return agentExecutorQueueCapacity; }
        public void setAgentExecutorQueueCapacity(int agentExecutorQueueCapacity) { this.agentExecutorQueueCapacity = agentExecutorQueueCapacity; }
        public int getWorkerBatchSize() { return workerBatchSize; }
        public void setWorkerBatchSize(int workerBatchSize) { this.workerBatchSize = workerBatchSize; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getStaleLeaseSeconds() { return staleLeaseSeconds; }
        public void setStaleLeaseSeconds(long staleLeaseSeconds) { this.staleLeaseSeconds = staleLeaseSeconds; }
        public long getWorkerDelayMillis() { return workerDelayMillis; }
        public void setWorkerDelayMillis(long workerDelayMillis) { this.workerDelayMillis = workerDelayMillis; }
        public boolean isMultiAgentEnabled() { return multiAgentEnabled; }
        public void setMultiAgentEnabled(boolean multiAgentEnabled) { this.multiAgentEnabled = multiAgentEnabled; }
        public int getMaxReviewRevisions() { return maxReviewRevisions; }
        public void setMaxReviewRevisions(int maxReviewRevisions) { this.maxReviewRevisions = maxReviewRevisions; }
        public int getRagLimit() { return ragLimit; }
        public void setRagLimit(int ragLimit) { this.ragLimit = ragLimit; }
        public int getAgentTimeoutSeconds() { return agentTimeoutSeconds; }
        public void setAgentTimeoutSeconds(int agentTimeoutSeconds) { this.agentTimeoutSeconds = agentTimeoutSeconds; }
    }

    public static class RemoteResilience {
        private int maxAttempts = 2;
        private int initialBackoffMillis = 200;
        private int failureThreshold = 5;
        private int openDurationSeconds = 30;
        private int maxConcurrentCalls = 8;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getInitialBackoffMillis() { return initialBackoffMillis; }
        public void setInitialBackoffMillis(int initialBackoffMillis) { this.initialBackoffMillis = initialBackoffMillis; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public int getOpenDurationSeconds() { return openDurationSeconds; }
        public void setOpenDurationSeconds(int openDurationSeconds) { this.openDurationSeconds = openDurationSeconds; }
        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
    }
}
