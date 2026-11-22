package com.example.mealcheck.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class KnowledgeContentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PgVectorKnowledgeService vectors;
    private final KnowledgeVectorPersistenceService persistence;
    public KnowledgeContentService(JdbcTemplate jdbc, ObjectMapper mapper, PgVectorKnowledgeService vectors,
                                   KnowledgeVectorPersistenceService persistence) {
        this.jdbc=jdbc; this.mapper=mapper; this.vectors=vectors; this.persistence=persistence;
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> history(Long chunkId, boolean deletedOnly, int page, int size) {
        int safePage=Math.max(0,Math.min(page,100000)), safeSize=Math.max(1,Math.min(size,50));
        String where=" WHERE (?::bigint IS NULL OR v.chunk_id=?)" + (deletedOnly
                ? " AND NOT EXISTS(SELECT 1 FROM knowledge_chunks k WHERE k.id=v.chunk_id) AND v.revision=(SELECT MAX(x.revision) FROM knowledge_content_versions x WHERE x.chunk_id=v.chunk_id)" : "");
        long total=jdbc.queryForObject("SELECT count(*) FROM knowledge_content_versions v"+where,Long.class,chunkId,chunkId);
        var rows=jdbc.queryForList("""
            SELECT v.id,v.chunk_id AS "chunkId",v.revision,v.operation,v.actor,v.reason,v.recorded_at AS "recordedAt",
                v.snapshot->>'title' AS title,
                COALESCE((SELECT content_revision FROM knowledge_chunks k WHERE k.id=v.chunk_id),
                    (SELECT MAX(revision) FROM knowledge_content_versions x WHERE x.chunk_id=v.chunk_id)) AS "currentRevision",
                NOT EXISTS(SELECT 1 FROM knowledge_chunks k WHERE k.id=v.chunk_id) AS deleted
            FROM knowledge_content_versions v
            """+where+" ORDER BY v.id DESC LIMIT ? OFFSET ?",chunkId,chunkId,safeSize,(long)safePage*safeSize);
        return Map.of("content",rows,"totalElements",total,"totalPages",(total+safeSize-1)/safeSize,"page",safePage,"size",safeSize);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> version(long versionId) {
        var rows=jdbc.queryForList("SELECT id,chunk_id AS \"chunkId\",revision,operation,actor,reason,recorded_at AS \"recordedAt\",snapshot::text AS snapshot FROM knowledge_content_versions WHERE id=?",versionId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"知识历史版本不存在。");
        var result=new LinkedHashMap<>(rows.get(0));
        try { result.put("snapshot",mapper.readValue((String)result.get("snapshot"),new TypeReference<Map<String,Object>>(){})); }
        catch(java.io.IOException error) { throw new IllegalStateException("Cannot read knowledge history",error); }
        result.put("current",jdbc.queryForList("SELECT title,content,content_revision AS revision FROM knowledge_chunks WHERE id=?",result.get("chunkId")));
        result.put("currentRevision",jdbc.queryForObject("SELECT MAX(revision) FROM knowledge_content_versions WHERE chunk_id=?",Long.class,result.get("chunkId")));
        return result;
    }
    @SuppressWarnings("unchecked")
    public void restore(long versionId, long expectedRevision, String actor, String reason) {
        var version=version(versionId);
        long id=((Number)version.get("chunkId")).longValue();
        long latest=jdbc.queryForObject("SELECT MAX(revision) FROM knowledge_content_versions WHERE chunk_id=?",Long.class,id);
        if(latest!=expectedRevision) throw new ResponseStatusException(HttpStatus.CONFLICT,"知识版本已变化，请刷新历史后重试。");
        var snapshot=(Map<String,Object>)version.get("snapshot");
        var prepared=vectors.prepareContent((String)snapshot.get("title"),(String)snapshot.get("content"));
        persistence.restore(id,expectedRevision,snapshot,prepared,actor,"恢复历史版本 #"+versionId+"："+reason);
    }
}
