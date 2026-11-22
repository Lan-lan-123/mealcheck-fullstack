package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.Set;

@Service
public class MealAnalysisRequestStore {
    @org.springframework.beans.factory.annotation.Value("${mealcheck.analysis.lease-seconds:90}")
    private int workerLeaseSeconds = 90;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.analysis.max-outstanding:200}")
    private int maxOutstanding = 200;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.analysis.max-stored-image-bytes:536870912}")
    private long maxStoredImageBytes = 536870912L;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.analysis.max-outstanding-per-user:2}")
    private int maxOutstandingPerUser = 2;
    @jakarta.annotation.PostConstruct
    public void validateAdmission() {
        if (maxOutstanding < 1 || maxOutstandingPerUser < 1 || maxOutstandingPerUser > maxOutstanding || maxStoredImageBytes < 1)
            throw new IllegalArgumentException("Invalid analysis outstanding limits");
    }
    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final UserGoalService goals;
    private final RateLimitService limits;
    private final MealRecordPersistenceService persistence;
    private final ObjectMapper mapper;
    private final TaskFailureRecorder failures;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ImageStorageService images;
    public MealAnalysisRequestStore(JdbcTemplate jdbc, UserAccountRepository users, UserGoalService goals,
            RateLimitService limits, MealRecordPersistenceService persistence, ObjectMapper mapper, TaskFailureRecorder failures) {
        this.jdbc = jdbc; this.users = users; this.goals = goals; this.limits = limits;
        this.persistence = persistence; this.mapper = mapper;
        this.failures = failures;
    }
    public record Claim(String token, String goal, MealAnalysisRequestResponse existing) { }
    @Transactional
    public String reserveLegacy(UserPrincipal principal) {
        users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户不存在"));
        String key="legacy-"+UUID.randomUUID();
        admit(principal.getId(),key);
        jdbc.update("""
                INSERT INTO meal_analysis_requests(user_id,request_key,payload_hash,effective_goal,status,attempt_token,lease_until)
                VALUES (?,?,?,'balanced','PROCESSING',?,CURRENT_TIMESTAMP+INTERVAL '20 minutes')
                """,principal.getId(),key,key,UUID.randomUUID().toString());
        return key;
    }
    @Transactional
    public void releaseLegacy(UserPrincipal principal,String key) {
        if (users.findByIdForUpdate(principal.getId()).isEmpty()) return;
        jdbc.update("DELETE FROM meal_analysis_requests WHERE user_id=? AND request_key=? AND input_image IS NULL AND input_image_ref IS NULL",principal.getId(),key);
    }
    @Transactional
    public Claim claim(UserPrincipal principal, String key, String hash, String goal) {
        return claimWithLease(principal,key,hash,goal,1200);
    }
    @Transactional
    public Claim claimUpload(UserPrincipal principal, String key, String hash, String goal) {
        return claimWithLease(principal,key,hash,goal,60);
    }
    private Claim claimWithLease(UserPrincipal principal, String key, String hash, String goal,int leaseSeconds) {
        var user = users.findByIdForUpdate(principal.getId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        var rows = jdbc.queryForList("SELECT payload_hash,status,effective_goal,lease_until > CURRENT_TIMESTAMP AS live FROM meal_analysis_requests WHERE user_id=? AND request_key=? FOR UPDATE", user.getId(), key);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            if (!hash.equals(row.get("payload_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一请求标识不能用于不同图片或目标");
            String status = row.get("status").toString();
            if ("PENDING".equals(status) || "SUCCEEDED".equals(status) || ("PROCESSING".equals(status)
                    && (boolean) row.get("live"))) {
                return new Claim(null, row.get("effective_goal").toString(), status(principal, key));
            }
            goal = row.get("effective_goal").toString();
        } else goal = goals.effectiveGoal(user, goal);
        admit(user.getId(), key);
        var limit = limits.consumeMealAnalysis(user.getUsername());
        if (limit.unavailable()) throw new IllegalStateException("限流服务暂时不可用，请稍后重试。");
        if (!limit.allowed()) throw new RateLimitExceededException("图片分析太频繁了，请稍后再试。", limit.limit(), limit.remaining(), limit.retryAfter());
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO meal_analysis_requests(user_id,request_key,payload_hash,effective_goal,status,attempt_token,lease_until)
                VALUES (?,?,?,?,'PROCESSING',?,CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                ON CONFLICT(user_id,request_key) DO UPDATE SET status='PROCESSING',attempt_token=EXCLUDED.attempt_token,
                  lease_until=EXCLUDED.lease_until,updated_at=CURRENT_TIMESTAMP
                """, user.getId(), key, hash, goal, token, leaseSeconds);
        failures.retrying("MEAL_ANALYSIS", user.getId(), key);
        return new Claim(token, goal, null);
    }
    @Transactional
    public MealAnalysisRequestResponse complete(UserPrincipal principal, String key, String token, MealAnalysisService.PreparedMeal prepared) {
        users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        var row = jdbc.queryForMap("SELECT status,attempt_token,lease_until > CURRENT_TIMESTAMP AS live FROM meal_analysis_requests WHERE user_id=? AND request_key=? FOR UPDATE", principal.getId(), key);
        if (!"PROCESSING".equals(row.get("status")) || !token.equals(row.get("attempt_token")) || !Boolean.TRUE.equals(row.get("live"))) return status(principal, key);
        String inputReference=jdbc.queryForObject("SELECT input_image_ref FROM meal_analysis_requests WHERE user_id=? AND request_key=?",String.class,principal.getId(),key);
        if(inputReference==null)persistence.saveWithImage(prepared.user(), prepared.record(), prepared.image());
        else persistence.saveWithStoredImage(prepared.user(),prepared.record(),inputReference);
        var result = prepared.response();
        jdbc.update("UPDATE meal_analysis_requests SET status='SUCCEEDED',record_id=?,response_json=?,input_image=NULL,input_image_ref=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND request_key=?",
                result.getRecordId(), json(result), principal.getId(), key);
        deleteDispatch(principal.getId(), key);
        failures.recovered("MEAL_ANALYSIS", principal.getId(), key);
        return new MealAnalysisRequestResponse(key, "SUCCEEDED", result, 0);
    }
    @Transactional
    public void fail(Long userId, String key, String token) {
        fail(userId, key, token, "UNKNOWN", null);
        // Retain the event identity for DLQ diagnosis until user retry, deletion or request retention.
        // The publisher excludes FAILED tasks, so this does not trigger automatic model retries.
    }
    @Transactional
    public void fail(Long userId, String key, String token, String phase, Throwable error) {
        if (users.findByIdForUpdate(userId).isEmpty()) return;
        int changed = jdbc.update("UPDATE meal_analysis_requests SET status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND request_key=? AND attempt_token=? AND status='PROCESSING' AND lease_until > CURRENT_TIMESTAMP", userId, key, token);
        if (changed == 1) failures.mealFailed(userId, key, token, phase, error);
    }
    @Transactional(readOnly = true)
    public MealAnalysisRequestResponse status(UserPrincipal principal, String key) {
        var rows = jdbc.queryForList("SELECT status,last_error,record_id,response_json,lease_until,lease_until > CURRENT_TIMESTAMP AS live, (input_image IS NOT NULL OR input_image_ref IS NOT NULL) AS has_input FROM meal_analysis_requests WHERE user_id=? AND request_key=?", principal.getId(), key);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析请求不存在");
        var row = rows.get(0);
        String status = row.get("status").toString();
        if ("SUCCEEDED".equals(status) && row.get("record_id") == null) throw new ResponseStatusException(HttpStatus.GONE, "分析记录已删除");
        boolean recovering = "PROCESSING".equals(status) && !Boolean.TRUE.equals(row.get("live"))
                && Boolean.TRUE.equals(row.get("has_input"));
        if (recovering) status = "PENDING";
        else if ("PROCESSING".equals(status) && !Boolean.TRUE.equals(row.get("live"))) status = "RETRYABLE";
        MealAnalysisResponse result = null;
        if ("SUCCEEDED".equals(status)) {
            try { result = mapper.readValue(row.get("response_json").toString(), MealAnalysisResponse.class); }
            catch (Exception error) { throw new IllegalStateException("无法读取分析结果", error); }
        }
        String errorCode = "FAILED".equals(status) && Set.of("AI_BUDGET_EXCEEDED","AI_BUDGET_UNAVAILABLE","UPLOAD_BUSY","ANALYSIS_STORAGE_BUSY",
                "NON_FOOD","NON_FOOD_LIMIT","IMAGE_UNCLEAR","RECOGNITION_INCONSISTENT","MODEL_SERVICE_ERROR","MODEL_RESPONSE_INVALID","MODEL_REQUEST_LIMITED","REMOTE_TIMEOUT","REMOTE_CONFIGURATION",
                "DEPENDENCY_BUSY","DEPENDENCY_UNAVAILABLE","DATABASE_UNAVAILABLE","RESULT_SAVE_FAILED","WORKER_INTERRUPTED","EXECUTION_FAILED").contains(String.valueOf(row.get("last_error")))
                ? row.get("last_error").toString() : null;
        if (recovering) return new MealAnalysisRequestResponse(key,status,null,3,null,"QUEUED",
                "处理进程中断，任务已保留，正在等待自动恢复；请勿重新上传。" );
        if ("PROCESSING".equals(status) && Boolean.FALSE.equals(row.get("has_input")))
            return new MealAnalysisRequestResponse(key,status,null,3,null,"ACCEPTED","上传请求已接收，正在校验图片并准备入队。" );
        return new MealAnalysisRequestResponse(key, status, result, ("PROCESSING".equals(status) || "PENDING".equals(status)) ? 3 : 0, errorCode);
    }

    @Transactional
    public MealAnalysisRequestResponse enqueue(UserPrincipal principal, String key, String hash, String goal, ValidatedImage image) {
        var user = users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        var rows = jdbc.queryForList("SELECT *,lease_until > CURRENT_TIMESTAMP AS live FROM meal_analysis_requests WHERE user_id=? AND request_key=? FOR UPDATE", user.getId(), key);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            if (!hash.equals(row.get("payload_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"同一请求标识不能用于不同图片或目标");
            String state = row.get("status").toString();
            if ("SUCCEEDED".equals(state) || "PENDING".equals(state) || ("PROCESSING".equals(state) && (boolean) row.get("live"))) return status(principal,key);
            goal = row.get("effective_goal").toString();
        } else goal = goals.effectiveGoal(user,goal);
        admit(user.getId(), key);
        var limit = limits.consumeMealAnalysis(user.getUsername());
        if (limit.unavailable()) throw new IllegalStateException("限流服务暂时不可用，请稍后重试。");
        if (!limit.allowed()) throw new RateLimitExceededException("图片分析太频繁了，请稍后再试。",limit.limit(),limit.remaining(),limit.retryAfter());
        admitImageBytes(user.getId(), key, image.size());
        jdbc.update("""
            INSERT INTO meal_analysis_requests(user_id,request_key,payload_hash,effective_goal,status,attempt_token,lease_until,
              input_image,input_content_type,input_extension,input_filename,input_width,input_height)
            VALUES (?,?,?,?,'PENDING',?,CURRENT_TIMESTAMP,?,?,?,?,?,?)
            ON CONFLICT(user_id,request_key) DO UPDATE SET status='PENDING',attempt_token=EXCLUDED.attempt_token,
              input_image=EXCLUDED.input_image,input_image_ref=NULL,input_content_type=EXCLUDED.input_content_type,input_extension=EXCLUDED.input_extension,
              input_filename=EXCLUDED.input_filename,input_width=EXCLUDED.input_width,input_height=EXCLUDED.input_height,
              attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP
            """,user.getId(),key,hash,goal,UUID.randomUUID().toString(),image.bytes(),image.contentType(),image.extension(),image.originalFilename(),image.width(),image.height());
        jdbc.update("""
            INSERT INTO meal_analysis_outbox(event_id,user_id,request_key) VALUES (?,?,?)
            ON CONFLICT(user_id,request_key) DO UPDATE SET event_id=EXCLUDED.event_id,
              next_publish_at=CURRENT_TIMESTAMP,publish_token=NULL,published_at=NULL,publish_attempts=0,
              created_at=CURRENT_TIMESTAMP
            """, UUID.randomUUID(), user.getId(), key);
        failures.retrying("MEAL_ANALYSIS", user.getId(), key);
        return new MealAnalysisRequestResponse(key,"PENDING",null,3);
    }

    /** The metadata has no image bytes; payloads are loaded after the claim transaction commits. */
    public record Work(UserPrincipal user,String key,String token,String goal,ValidatedImage imageMetadata,long queueWaitMillis,String imageReference) {
        public Work(UserPrincipal user,String key,String token,String goal,ValidatedImage imageMetadata) { this(user,key,token,goal,imageMetadata,0,null); }
        public Work(UserPrincipal user,String key,String token,String goal,ValidatedImage imageMetadata,long queueWaitMillis) { this(user,key,token,goal,imageMetadata,queueWaitMillis,null); }
    }
    /** Do not fetch payloads while an account/request lock is held by a caller transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public ValidatedImage loadImage(Work work) {
        AiCallContext.checkExecution();
        byte[] bytes;
        if (work.imageReference() == null) {
            var payloads = jdbc.query("""
                SELECT input_image FROM meal_analysis_requests
                WHERE user_id=? AND request_key=? AND attempt_token=? AND status='PROCESSING'
                  AND lease_until>CURRENT_TIMESTAMP AND input_image_ref IS NULL AND input_image IS NOT NULL
                """, (rs, row) -> rs.getBytes(1), work.user().getId(), work.key(), work.token());
            if (payloads.isEmpty()) throw new WorkNoLongerOwnedException();
            bytes = payloads.get(0);
        } else {
            requireLiveWork(work);
            if (images == null) throw new IllegalStateException("Image storage is unavailable");
            bytes = images.read(work.imageReference());
        }
        AiCallContext.checkExecution();
        // Deletion, expiry or replacement during the read must stop before model work.
        requireLiveWork(work);
        var metadata=work.imageMetadata();
        return new ValidatedImage(bytes,metadata.contentType(),metadata.extension(),metadata.width(),metadata.height(),metadata.originalFilename());
    }
    private void requireLiveWork(Work work) {
        Long live = jdbc.queryForObject("""
            SELECT count(*) FROM meal_analysis_requests
            WHERE user_id=? AND request_key=? AND attempt_token=? AND status='PROCESSING'
              AND lease_until>CURRENT_TIMESTAMP AND input_image_ref IS NOT DISTINCT FROM ?
              AND (input_image IS NOT NULL OR input_image_ref IS NOT NULL)
            """, Long.class, work.user().getId(), work.key(), work.token(), work.imageReference());
        if (live == null || live != 1) throw new WorkNoLongerOwnedException();
    }
    public static final class WorkNoLongerOwnedException extends RuntimeException {
        public WorkNoLongerOwnedException() { super("Analysis execution is no longer owned"); }
    }
    @Transactional
    public MealAnalysisRequestResponse enqueueClaimedReference(UserPrincipal principal,String key,String token,ValidatedImage image,String reference) {
        users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户不存在"));
        int changed=jdbc.update("""
                UPDATE meal_analysis_requests SET status='PENDING',input_image=NULL,input_image_ref=?,input_content_type=?,input_extension=?,
                  input_filename=?,input_width=?,input_height=?,attempts=0,next_attempt_at=CURRENT_TIMESTAMP,
                  lease_until=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND request_key=? AND attempt_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP
                """,reference,image.contentType(),image.extension(),image.originalFilename(),image.width(),image.height(),principal.getId(),key,token);
        if(changed==0)return status(principal,key);
        if(images != null)images.deleteOnRollback(reference);
        jdbc.update("""
                INSERT INTO meal_analysis_outbox(event_id,user_id,request_key) VALUES (?,?,?)
                ON CONFLICT(user_id,request_key) DO UPDATE SET event_id=EXCLUDED.event_id,next_publish_at=CURRENT_TIMESTAMP,
                  publish_token=NULL,published_at=NULL,publish_attempts=0,created_at=CURRENT_TIMESTAMP
                """,UUID.randomUUID(),principal.getId(),key);
        return new MealAnalysisRequestResponse(key,"PENDING",null,3);
    }
    @Transactional
    public MealAnalysisRequestResponse enqueueClaimed(UserPrincipal principal, String key, String token, ValidatedImage image) {
        users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        admitImageBytes(principal.getId(), key, image.size());
        int changed = jdbc.update("""
                UPDATE meal_analysis_requests SET status='PENDING',input_image=?,input_image_ref=NULL,input_content_type=?,input_extension=?,
                  input_filename=?,input_width=?,input_height=?,attempts=0,next_attempt_at=CURRENT_TIMESTAMP,
                  lease_until=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND request_key=? AND attempt_token=? AND status='PROCESSING' AND lease_until > CURRENT_TIMESTAMP
                """, image.bytes(), image.contentType(), image.extension(), image.originalFilename(), image.width(), image.height(),
                principal.getId(), key, token);
        if (changed == 0) return status(principal, key);
        jdbc.update("""
                INSERT INTO meal_analysis_outbox(event_id,user_id,request_key) VALUES (?,?,?)
                ON CONFLICT(user_id,request_key) DO UPDATE SET event_id=EXCLUDED.event_id,next_publish_at=CURRENT_TIMESTAMP,
                  publish_token=NULL,published_at=NULL,publish_attempts=0,created_at=CURRENT_TIMESTAMP
                """, UUID.randomUUID(), principal.getId(), key);
        return new MealAnalysisRequestResponse(key, "PENDING", null, 3);
    }
    @Transactional
    public Work next() {
        // Always acquire account before request row, as completion/deletion do.
        var ids = jdbc.queryForList("""
            SELECT user_id,request_key FROM meal_analysis_requests WHERE (input_image IS NOT NULL OR input_image_ref IS NOT NULL)
              AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (status='PENDING' OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP))
            ORDER BY next_attempt_at,created_at LIMIT 1
            """);
        if (ids.isEmpty()) return null;
        Long userId=((Number)ids.get(0).get("user_id")).longValue();
        return take(userId, ids.get(0).get("request_key").toString(), null);
    }

    @Transactional
    public Work takeMessage(UUID eventId) {
        var rows = jdbc.queryForList("SELECT user_id,request_key FROM meal_analysis_outbox WHERE event_id=?", eventId);
        if (rows.isEmpty()) return null;
        return take(((Number) rows.get(0).get("user_id")).longValue(), rows.get(0).get("request_key").toString(), eventId);
    }

    private Work take(Long userId, String key, UUID eventId) {
        var user=users.findByIdForUpdate(userId).orElse(null);
        if (user==null) return null;
        // Recheck after the account lock: a retry may have replaced this dispatch identity.
        if (eventId != null && jdbc.queryForObject(
                "SELECT count(*) FROM meal_analysis_outbox WHERE event_id=? AND user_id=? AND request_key=?",
                Long.class, eventId, userId, key) == 0) return null;
        var rows=jdbc.queryForList("""
            SELECT r.attempts,r.attempt_token,r.effective_goal,r.input_image_ref,
              r.input_content_type,r.input_extension,r.input_width,r.input_height,r.input_filename,
              GREATEST(0,EXTRACT(EPOCH FROM (clock_timestamp()-coalesce(
                (SELECT o.created_at FROM meal_analysis_outbox o WHERE o.user_id=r.user_id AND o.request_key=r.request_key),r.next_attempt_at)))) AS queue_wait_seconds
            FROM meal_analysis_requests r WHERE user_id=? AND request_key=? AND (input_image IS NOT NULL OR input_image_ref IS NOT NULL)
              AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (status='PENDING' OR (status='PROCESSING' AND lease_until <= CURRENT_TIMESTAMP)) FOR UPDATE
            """,userId,key);
        if (rows.isEmpty()) return null;
        var row=rows.get(0);
        if (((Number)row.get("attempts")).intValue()>=3) {
            jdbc.update("UPDATE meal_analysis_requests SET status='FAILED',last_error='WORKER_INTERRUPTED',updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND request_key=?",userId,key);
            failures.record("MEAL_ANALYSIS", userId, key, eventId, row.get("attempt_token").toString(),
                    ((Number)row.get("attempts")).intValue(), "WORKER",
                    new TaskFailureClassifier.Failure("WORKER_INTERRUPTED", "执行中断次数已达到上限", true), true);
            return null;
        }
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE meal_analysis_requests SET status='PROCESSING',attempt_token=?,attempts=attempts+1,lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND request_key=?",
                token,MealAnalysisLeaseService.boundedSeconds(workerLeaseSeconds),userId,key);
        return new Work(new UserPrincipal(user),key,token,row.get("effective_goal").toString(),new ValidatedImage(
            new byte[0],row.get("input_content_type").toString(),row.get("input_extension").toString(),
            ((Number)row.get("input_width")).intValue(),((Number)row.get("input_height")).intValue(),row.get("input_filename").toString()),
            Math.round(((Number)row.get("queue_wait_seconds")).doubleValue()*1000),(String)row.get("input_image_ref"));
    }
    private void deleteDispatch(Long userId, String key) {
        jdbc.update("DELETE FROM meal_analysis_outbox WHERE user_id=? AND request_key=?", userId, key);
    }
    private void admit(Long userId, String key) {
        // Held until the claim commits: all instances count and reserve in the same transaction.
        // Count preparation, queued work (including unpublished outbox), and expired worker leases.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(742019001)", (rs, n) -> 0);
        var counts = jdbc.queryForMap("""
                SELECT count(*) AS total, count(*) FILTER (WHERE user_id=?) AS owned
                FROM meal_analysis_requests WHERE (status='PENDING' OR (status='PROCESSING'
                  AND (input_image IS NOT NULL OR input_image_ref IS NOT NULL OR lease_until > CURRENT_TIMESTAMP)))
                  AND NOT (user_id=? AND request_key=?)
                """, userId, userId, key);
        if (((Number) counts.get("owned")).longValue() >= Math.max(1, maxOutstandingPerUser))
            throw new AnalysisAdmissionException("ANALYSIS_USER_BUSY", "您已有待完成的图片分析，请等待完成后再提交。");
        if (((Number) counts.get("total")).longValue() >= Math.max(1, maxOutstanding))
            throw new AnalysisAdmissionException("ANALYSIS_BUSY", "图片分析任务较多，请稍后再试。");
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("无法保存分析结果", error); }
    }
    private void admitImageBytes(Long userId, String key, long incomingBytes) {
        // Use the same global lock as count admission. Failed payloads also occupy disk
        // until retention removes them; replacement of this request is counted only once.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(742019001)", (rs, n) -> 0);
        Long stored = jdbc.queryForObject("""
                SELECT coalesce(sum(octet_length(input_image)),0)::bigint FROM meal_analysis_requests
                WHERE input_image IS NOT NULL AND NOT (user_id=? AND request_key=?)
                """, Long.class, userId, key);
        if (stored == null || incomingBytes < 0 || incomingBytes > maxStoredImageBytes
                || stored > maxStoredImageBytes - incomingBytes)
            throw new AnalysisAdmissionException("ANALYSIS_STORAGE_BUSY", "待处理图片存储空间繁忙，请等待已有任务完成后再试。");
    }
}
