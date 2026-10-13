package com.example.mealcheck.service;

import com.example.mealcheck.dto.JournalDtos.Feedback;
import com.example.mealcheck.dto.TrackingDtos.AnswerFeedback;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;

@Service
public class AnswerFeedbackService {
    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    public AnswerFeedbackService(JdbcTemplate jdbc, UserAccountRepository users) { this.jdbc=jdbc;this.users=users; }
    @Transactional
    public void save(UserPrincipal user,long id,Feedback body) {
        users.findByIdForUpdate(user.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var requests=jdbc.query("SELECT m.request_id FROM assistant_conversation_messages m JOIN assistant_conversations c ON c.id=m.conversation_id WHERE m.id=? AND c.user_id=? AND m.role='assistant'",(rs,n)->Optional.ofNullable(rs.getString(1)),id,user.getId());
        if(requests.isEmpty())throw JournalValidation.missing("回答");
        jdbc.update("INSERT INTO assistant_answer_feedback(message_id,user_id,verdict,category,note,request_id) VALUES(?,?,?,?,?,?) ON CONFLICT(message_id) DO UPDATE SET verdict=EXCLUDED.verdict,category=EXCLUDED.category,note=EXCLUDED.note,request_id=EXCLUDED.request_id,updated_at=CURRENT_TIMESTAMP",id,user.getId(),body.verdict(),body.category(),body.note(),requests.get(0).orElse(null));
    }
    @Transactional(readOnly=true)
    public AnswerFeedback get(long userId,long id) {
        return jdbc.query("SELECT verdict,category,note,request_id FROM assistant_answer_feedback WHERE message_id=? AND user_id=?",(rs,n)->new AnswerFeedback(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),id,userId).stream().findFirst().orElse(null);
    }
    public void requireConversation(long userId,Long id) {
        if(id!=null && jdbc.queryForObject("SELECT count(*) FROM assistant_conversations WHERE id=? AND user_id=?",Long.class,id,userId)!=1)throw JournalValidation.missing("会话");
    }
}
