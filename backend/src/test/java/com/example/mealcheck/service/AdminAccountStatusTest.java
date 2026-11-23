package com.example.mealcheck.service;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAccountStatusTest {
    @Test void statusChangesInvalidateTokensAndAreIdempotentAndAudited() {
        var users = mock(UserAccountRepository.class);
        var audit = mock(AdminAuditService.class);
        var target = account(10L, "alice", "USER");
        var admin = new UserPrincipal(account(1L, "admin", "ADMIN"));
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
        var service = service(users, audit);
        assertThat(service.setUserEnabled(10L, false, admin).isEnabled()).isFalse();
        assertThat(target.getTokenVersion()).isEqualTo(1);
        service.setUserEnabled(10L, false, admin);
        assertThat(target.getTokenVersion()).isEqualTo(1);
        assertThat(service.setUserEnabled(10L, true, admin).isEnabled()).isTrue();
        assertThat(target.getTokenVersion()).isEqualTo(2);
        verify(users, times(2)).saveAndFlush(target);
        verify(audit).log(admin, "BAN_USER", "user", 10L, "封禁用户 alice");
        verify(audit).log(admin, "UNBAN_USER", "user", 10L, "解封用户 alice");
        verifyNoMoreInteractions(audit);
    }

    @Test void administratorsAndMissingAccountsCannotBeChanged() {
        var users = mock(UserAccountRepository.class);
        var audit = mock(AdminAuditService.class);
        var target = account(1L, "admin", "ADMIN");
        var principal = new UserPrincipal(target);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(target));
        var service = service(users, audit);
        for (boolean enabled : new boolean[]{false, true}) {
            assertThatThrownBy(() -> service.setUserEnabled(1L, enabled, principal))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> service.setUserEnabled(99L, false, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        assertThat(target.isEnabled()).isTrue();
        assertThat(target.getTokenVersion()).isZero();
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    // Only the status-change dependencies are used in these tests.
    static AdminService service(UserAccountRepository users, AdminAuditService audit) {
        return new AdminService(users, null, null, null, null, null, null, null, null,
                null, null, audit, null, null, null, null, null, null, null);
    }

    static UserAccount account(long id, String username, String role) {
        var user = new UserAccount(); user.setId(id); user.setUsername(username);
        user.setPasswordHash("hash"); user.setRole(role);
        return user;
    }
}
