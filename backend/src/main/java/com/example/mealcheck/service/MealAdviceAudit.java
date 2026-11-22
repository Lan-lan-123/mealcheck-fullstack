package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Opt-in diagnostic evidence for explicitly authorized fixture users only. Never an operational text log. */
@Service
public class MealAdviceAudit {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MealAdviceAudit.class);
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Set<Long> users;
    private final Path logsRoot,directory;
    private final int maxFiles;

    public MealAdviceAudit(ObjectMapper mapper,
            @Value("${mealcheck.advice-audit.enabled:false}") boolean enabled,
            @Value("${mealcheck.advice-audit.user-ids:}") String userIds,
            @Value("${mealcheck.advice-audit.directory:}") String directory,
            @Value("${mealcheck.advice-audit.max-files:500}") int maxFiles) {
        this.mapper=mapper;this.enabled=enabled;this.maxFiles=maxFiles;
        Path root=Path.of("").toAbsolutePath().normalize();
        if(root.getFileName()!=null && root.getFileName().toString().equals("backend") && Files.isRegularFile(root.resolve("pom.xml")))root=root.getParent();
        logsRoot=root.resolve("logs");
        this.directory=directory==null || directory.isBlank()?logsRoot.resolve("advice-audit"):Path.of(directory).toAbsolutePath().normalize();
        if(!this.directory.startsWith(logsRoot))throw new IllegalArgumentException("Advice audit directory must be inside project logs");
        if(maxFiles<1 || maxFiles>10000)throw new IllegalArgumentException("Advice audit file limit must be 1..10000");
        var ids=new HashSet<Long>();
        if(enabled) {
            if(userIds==null || userIds.isBlank())throw new IllegalArgumentException("Advice audit requires explicit authorized fixture user IDs");
            for(String value:userIds.split(",",-1)) {
                long id=Long.parseLong(value.trim());if(id<1)throw new IllegalArgumentException("Invalid advice audit fixture ID");ids.add(id);
            }
            if(ids.size()>1000)throw new IllegalArgumentException("Too many advice audit fixture users");
        }
        users=Set.copyOf(ids);
    }

    void record(String candidate,MealAdviceConsistency.Assessment assessment,boolean accepted) {
        var context=AiCallContext.capture();
        if(!enabled || context==null || context.userId()==null || !users.contains(context.userId()) || assessment.reasons().isEmpty())return;
        try {
            Files.createDirectories(directory);
            if(!directory.toRealPath().startsWith(logsRoot.toRealPath()))throw new java.io.IOException("Audit path escaped logs");
            synchronized(this) {
                try(var entries=Files.list(directory)) { if(entries.limit(maxFiles).count()>=maxFiles)return; }
                var event=mapper.createObjectNode().put("schemaVersion",1).put("at",Instant.now().toString()).put("fixtureUserId",context.userId());
                event.put("disposition",accepted?"FILTERED_REVIEW":"RULE_FALLBACK");
                event.put("candidateSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((candidate==null?"":candidate).getBytes(StandardCharsets.UTF_8))));
                event.set("reasons",mapper.valueToTree(assessment.reasons()));
                var details=event.putArray("findings");
                for(var finding:assessment.findings()) {
                    var item=details.addObject().put("reason",finding.reason().name()).put("branch",finding.branch())
                            .put("sentence",redact(finding.sentence())).put("food",redact(finding.food())).put("attribute",finding.attribute());
                    if(finding.knownValue()==null)item.putNull("knownValue");else item.put("knownValue",finding.knownValue());
                }
                event.put("findingsLimit",32).put("scope","Explicitly authorized synthetic fixture account; no full candidate, prompt, image, API credentials or user history.");
                String request=org.slf4j.MDC.get("requestId");
                if(request!=null && request.matches("[a-fA-F0-9-]{36}"))event.put("requestId",request);
                Path target=directory.resolve("advice-"+UUID.randomUUID()+".json");
                Files.writeString(target,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            }
        } catch(Exception unavailable) {
            // A diagnostic write cannot replace or fail a valid meal result. Never log candidate text or exception messages.
            log.warn("Meal advice audit skipped. type={}",unavailable.getClass().getSimpleName());
        }
    }
    private static String redact(String text) {
        return text.replaceAll("(?i)sk-[a-z0-9_-]+","[REDACTED]")
                .replaceAll("(?i)Bearer\\s+[^\\s，,。；;]+","Bearer [REDACTED]");
    }
}
