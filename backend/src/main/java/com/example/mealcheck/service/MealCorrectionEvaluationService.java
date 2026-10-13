package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.dto.MealCorrectionRequest.CorrectedFood;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MealCorrectionEvaluationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MealScoringService scoring;
    public MealCorrectionEvaluationService(JdbcTemplate jdbc, ObjectMapper mapper, MealScoringService scoring) {
        this.jdbc=jdbc; this.mapper=mapper; this.scoring=scoring;
    }
    public record Review(@Pattern(regexp="APPROVED|REJECTED") @NotBlank String status,
            @AssertTrue boolean sanitizedConfirmed,
            @NotEmpty @Size(max=30) List<@NotNull @Valid CorrectedFood> foods,
            @NotNull @Min(0) @Max(100) Integer expectedScore) { }
    public record Candidate(Long id, long revision, String beforeFoods, String afterFoods, String reason,
            String goal, String status, Integer expectedScore) { }
    public record EvaluationCase(String id, String goal, List<FoodItem> foods, int expectedScore, int actualScore, boolean passed) { }
    private record Fixture(String id, String goal, List<FoodItem> foods, int expectedScore) { }
    public record Evaluation(String datasetVersion, String datasetFingerprint, String scoringVersion,
            int caseCount, int failedCount, boolean passed, List<EvaluationCase> cases) { }
    public record Run(long id,java.time.LocalDateTime createdAt,String datasetFingerprint,String scoringVersion,int caseCount,int failedCount,boolean passed) { }
    public record Difference(String id,Integer before,Integer after,Boolean beforePassed,Boolean afterPassed) { }
    public record Comparison(long baseId,long candidateId,boolean comparable,List<Difference> differences) { }

    @Transactional
    public Run run() {
        var result=evaluate();
        if (result.caseCount()==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先审核至少一个样本");
        String fixtures=writeJson(result.cases().stream().map(item -> new Fixture(item.id(),item.goal(),item.foods(),item.expectedScore())).toList());
        Long dataset=jdbc.queryForObject("""
            INSERT INTO meal_evaluation_datasets(fingerprint,dataset_version,fixtures_json) VALUES (?,?,?)
            ON CONFLICT(fingerprint) DO UPDATE SET fingerprint=EXCLUDED.fingerprint RETURNING id
            """,Long.class,result.datasetFingerprint(),result.datasetVersion(),fixtures);
        Long id=jdbc.queryForObject("INSERT INTO meal_evaluation_runs(dataset_id,scoring_version,result_json) VALUES (?,?,?) RETURNING id",Long.class,dataset,result.scoringVersion(),writeJson(result));
        java.time.LocalDateTime createdAt=jdbc.queryForObject("SELECT created_at FROM meal_evaluation_runs WHERE id=?",(rs,n)->rs.getObject(1,java.time.LocalDateTime.class),id);
        return new Run(id,createdAt,result.datasetFingerprint(),result.scoringVersion(),result.caseCount(),result.failedCount(),result.passed());
    }
    @Transactional(readOnly=true)
    public List<Run> history(int page) {
        return jdbc.query("""
            SELECT r.id,r.created_at,r.scoring_version,r.result_json,d.fingerprint FROM meal_evaluation_runs r
            JOIN meal_evaluation_datasets d ON r.dataset_id=d.id ORDER BY r.id DESC LIMIT 20 OFFSET ?
            """,(rs,index) -> {
                var result=readEvaluation(rs.getString("result_json"));
                return new Run(rs.getLong("id"),rs.getObject("created_at",java.time.LocalDateTime.class),rs.getString("fingerprint"),rs.getString("scoring_version"),result.caseCount(),result.failedCount(),result.passed());
            },Math.max(0,page)*20L);
    }
    @Transactional(readOnly=true)
    public Evaluation detail(long id) {
        var rows=jdbc.queryForList("SELECT result_json FROM meal_evaluation_runs WHERE id=?",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"评测记录不存在");
        return readEvaluation(rows.get(0).get("result_json").toString());
    }
    @Transactional(readOnly=true)
    public Comparison compare(long baseId,long candidateId) {
        var base=detail(baseId);var candidate=detail(candidateId);
        Map<String,EvaluationCase> left=new LinkedHashMap<>(),right=new LinkedHashMap<>();
        base.cases().forEach(item -> left.put(item.id(),item));candidate.cases().forEach(item -> right.put(item.id(),item));
        Set<String> keys=new LinkedHashSet<>(left.keySet());keys.addAll(right.keySet());
        var differences=keys.stream().map(key -> {
            var a=left.get(key);var b=right.get(key);
            return new Difference(key,a==null?null:a.actualScore(),b==null?null:b.actualScore(),a==null?null:a.passed(),b==null?null:b.passed());
        }).toList();
        return new Comparison(baseId,candidateId,base.datasetFingerprint().equals(candidate.datasetFingerprint()),differences);
    }
    private Evaluation readEvaluation(String json) {
        try { return mapper.readValue(json,Evaluation.class); }
        catch (Exception error) { throw new IllegalStateException("无法读取评测历史",error); }
    }

    @Transactional(readOnly=true)
    public List<Candidate> candidates(int page) {
        return jdbc.query("""
            SELECT c.*, m.goal FROM meal_corrections c JOIN meal_records m ON m.id=c.record_id
            ORDER BY c.id DESC LIMIT 50 OFFSET ?
            """, (rs, index) -> new Candidate(rs.getLong("id"), rs.getLong("revision"), rs.getString("before_foods_json"),
                rs.getString("after_foods_json"), rs.getString("reason"), rs.getString("goal"), rs.getString("evaluation_status"),
                (Integer) rs.getObject("expected_score")), Math.max(0, page) * 50L);
    }
    @Transactional
    public void review(Long id, Review request, String reviewer) {
        // The reviewer supplies a separate sanitized fixture and expected score.
        // No account, image, free-text correction reason or timestamps are exported.
        var recognition = new RecognitionResult();
        recognition.setFoods(request.foods().stream().map(food -> new FoodItem(food.name().trim(), food.category(), 1.0, null)).toList());
        String json = writeJson(recognition.getFoods());
        int updated = jdbc.update("UPDATE meal_corrections SET evaluation_status=?,evaluation_foods_json=?,expected_score=?,reviewed_by=? WHERE id=?",
                request.status(), json, request.expectedScore(), reviewer, id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "纠错样本不存在");
    }
    @Transactional(readOnly=true)
    public Evaluation evaluate() {
        List<EvaluationCase> cases = jdbc.query("""
            SELECT c.id,c.evaluation_foods_json,c.expected_score,m.goal FROM meal_corrections c
            JOIN meal_records m ON m.id=c.record_id WHERE c.evaluation_status='APPROVED' ORDER BY c.id
            """, (rs, index) -> {
                try {
                    List<FoodItem> foods = mapper.readValue(rs.getString("evaluation_foods_json"), new TypeReference<>() {});
                    var recognition = new RecognitionResult(); recognition.setFoods(foods);
                    int actual = scoring.evaluate(recognition, rs.getString("goal")).getScore();
                    return new EvaluationCase("correction-" + rs.getLong("id"), rs.getString("goal"), foods,
                            rs.getInt("expected_score"), actual, actual == rs.getInt("expected_score"));
                } catch (Exception error) { throw new IllegalStateException("无法评测纠错样本", error); }
            });
        int failed = (int) cases.stream().filter(item -> !item.passed()).count();
        // Fingerprint depends only on the approved inputs and human labels, not on
        // the scorer's actual output, so runs remain comparable after code changes.
        var fixture = cases.stream().map(item -> new Fixture(item.id(), item.goal(), item.foods(), item.expectedScore())).toList();
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(writeJson(fixture).getBytes(StandardCharsets.UTF_8)));
            return new Evaluation("meal-corrections-v1", fingerprint, MealScoringService.VERSION,
                    cases.size(), failed, !cases.isEmpty() && failed == 0, cases);
        } catch (Exception error) { throw new IllegalStateException("无法计算评测集指纹", error); }
    }
    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("无法序列化评测样本", error); }
    }
}
