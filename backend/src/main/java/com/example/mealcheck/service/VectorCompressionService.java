package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class VectorCompressionService {
    private final AppProperties properties;

    public VectorCompressionService(AppProperties properties) {
        this.properties = properties;
    }

    public StorageType storageType() {
        String configured = properties.getKnowledge().getVectorStorage();
        if (configured == null) {
            return StorageType.HALFVEC;
        }
        return "vector".equals(configured.trim().toLowerCase(Locale.ROOT))
                ? StorageType.VECTOR
                : StorageType.HALFVEC;
    }

    public String columnType(int dimension) {
        return storageType().sqlName() + "(" + dimension + ")";
    }

    public String castParameter() {
        return "CAST(? AS " + storageType().sqlName() + ")";
    }

    public String cosineOperatorClass() {
        return storageType().sqlName() + "_cosine_ops";
    }

    public String signatureSuffix() {
        return storageType().sqlName() + "-v1";
    }

    public int estimatedBytesPerVector(int dimension) {
        return storageType().bytesPerDimension() * dimension + 8;
    }

    public enum StorageType {
        VECTOR("vector", 4),
        HALFVEC("halfvec", 2);

        private final String sqlName;
        private final int bytesPerDimension;

        StorageType(String sqlName, int bytesPerDimension) {
            this.sqlName = sqlName;
            this.bytesPerDimension = bytesPerDimension;
        }

        public String sqlName() { return sqlName; }
        public int bytesPerDimension() { return bytesPerDimension; }
    }
}
