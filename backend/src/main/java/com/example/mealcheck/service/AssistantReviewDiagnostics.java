package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** Operational logs contain finite codes/counts only. Text evidence is opt-in for authorized fixtures. */
@Service
public class AssistantReviewDiagnostics {
    private final ObjectMapper mapper;
    private final Set<Long> fixtureUsers;
    private final Path root,directory;
    private final int limit;
    public AssistantReviewDiagnostics(ObjectMapper mapper,
            @Value("${mealcheck.assistant-review-audit.enabled:false}") boolean enabled,
            @Value("${mealcheck.assistant-review-audit.user-ids:}") String users,
            @Value("${mealcheck.assistant-review-audit.parent-directory:}") String parent,
            @Value("${mealcheck.assistant-review-audit.max-files:500}") int limit) {
        this.mapper=mapper;this.limit=limit;
        root=diagnosticRoot(Path.of(""));
        directory=(parent==null || parent.isBlank()?root.resolve("advice-audit"):Path.of(parent).toAbsolutePath().normalize()).resolve("review");
        if(!directory.startsWith(root) || limit<1 || limit>10000)throw new IllegalArgumentException("Invalid review diagnostic storage");
        var ids=new HashSet<Long>();
        if(enabled) {
            if(users==null || users.isBlank())throw new IllegalArgumentException("Review fixture audit needs explicit IDs");
            for(String value:users.split(",")){long id=Long.parseLong(value.trim());if(id<1)throw new IllegalArgumentException("Invalid fixture ID");ids.add(id);}
        }
        fixtureUsers=Set.copyOf(ids);
    }
    static Path diagnosticRoot(Path workingDirectory) {
        Path cwd=workingDirectory.toAbsolutePath().normalize();
        if(cwd.getFileName()!=null && cwd.getFileName().toString().equals("backend")
                && Files.isRegularFile(cwd.resolve("pom.xml")))cwd=cwd.getParent();
        return cwd.resolve("logs");
    }
    static Map<String,Object> summary(String stage,int round,JsonNode verdict,List<AssistantGroundingGuard.Issue> issues) {
        var codes=new TreeSet<String>();
        for(var issue:issues)if(issue.code().matches("[A-Z_]{1,60}"))codes.add(issue.code());
        var failed=new TreeSet<String>();int unsupported=0,incomplete=0;
        if(verdict!=null)for(var claim:verdict.path("claims"))for(var assertion:claim.path("assertions")) {
            if(assertion.path("supported").isBoolean() && !assertion.path("supported").asBoolean())unsupported++;
            for(String check:List.of("fact","degree","causality","conditions")) {
                var value=assertion.path("checks").path(check);
                if(!value.isBoolean())incomplete++;else if(!value.asBoolean())failed.add(check);
            }
        }
        var result=new LinkedHashMap<String,Object>();result.put("stage",stage);result.put("round",round);
        result.put("localCodes",codes);result.put("failedChecks",failed);result.put("unsupportedAssertions",unsupported);result.put("missingCheckFields",incomplete);
        result.put("answersQuestion",verdict==null || !verdict.path("answersQuestion").isBoolean()?"UNAVAILABLE":Boolean.toString(verdict.path("answersQuestion").asBoolean()));
        result.put("inputMissingCount",verdict==null?0:verdict.path("inputMissing").size());result.put("knowledgeMissingCount",verdict==null?0:verdict.path("knowledgeMissing").size());
        return result;
    }
    synchronized void record(Map<String,Object> summary,JsonNode candidate,JsonNode verdict,List<AssistantGroundingGuard.Issue> issues) {
        var context=AiCallContext.capture();
        if(context==null || context.userId()==null || !fixtureUsers.contains(context.userId()))return;
        try {
            Files.createDirectories(directory);
            if(!directory.toRealPath().startsWith(root.toRealPath()))return;
            try(var files=Files.list(directory)){if(files.limit(limit).count()>=limit)return;}
            ObjectNode event=mapper.createObjectNode().put("fixtureUserId",context.userId()).put("at",java.time.Instant.now().toString());
            String request=org.slf4j.MDC.get("requestId");if(request!=null && request.matches("[a-fA-F0-9-]{36}"))event.put("requestId",request);
            event.set("summary",mapper.valueToTree(summary));
            if(candidate!=null)event.set("candidate",candidate.deepCopy());if(verdict!=null)event.set("verdict",verdict.deepCopy());
            event.set("issues",mapper.valueToTree(issues));
            String text=mapper.writeValueAsString(event);
            if(text.length()>131072)return;
            text=text.replaceAll("(?i)sk-[a-z0-9_-]+","[REDACTED]").replaceAll("(?i)Bearer\\s+[^\\s\"，,。；;]+","Bearer [REDACTED]");
            Files.writeString(directory.resolve("review-"+UUID.randomUUID()+".json"),text,StandardOpenOption.CREATE_NEW);
        } catch(Exception failure) {
            org.slf4j.LoggerFactory.getLogger(AssistantReviewDiagnostics.class).warn("Review fixture audit skipped. type={}",failure.getClass().getSimpleName());
        }
    }
}
