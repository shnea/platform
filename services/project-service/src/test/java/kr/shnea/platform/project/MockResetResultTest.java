package kr.shnea.platform.project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MockResetResultTest {
    @SuppressWarnings("unchecked")
    @Test void partialResultCommitsIntentAndOutcomeForEachUserAndRejectsStaleBatch() {
        UUID environmentId = UUID.randomUUID(), projectId = UUID.randomUUID();
        var env = new ProjectService.Environment(environmentId, projectId, "dev", "DEV", "test", false, List.of(), "READY", "test", 0);
        var project = new ProjectService.Project(projectId, "test", "test", Instant.now(), "ACTIVE", 0, false);
        var first = new MockReset.Target(UUID.randomUUID(), "one", "google");
        var second = new MockReset.Target(UUID.randomUUID(), "two", "google");
        var plan = new MockReset.Preview(List.of(first, second), false, "a".repeat(64));
        var db = mock(JdbcTemplate.class);
        var identity = mock(IdentityClient.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        doAnswer(call -> ((String) call.getArgument(0)).contains("FROM environments") ? List.of(env) : List.of(project))
            .when(db).query(anyString(), any(RowMapper.class), any(UUID.class));
        when(identity.mockResetPreview(env)).thenReturn(plan);
        doThrow(new RestClientException("private provider body")).when(identity).deleteMockUser(env, second.id());
        var service = new ProjectService(db, new TransactionTemplate(manager), identity, "dev");
        var request = new MockController.Reset(List.of(first.id(), second.id()), plan.revision());
        var result = service.resetMockUsers(environmentId, request, "operator");
        assertEquals(2, result.requested());
        assertEquals(1, result.deleted());
        assertEquals(1, result.failed());
        assertEquals(List.of("DELETED", "FAILED"), result.items().stream().map(MockReset.Item::status).toList());
        var order = inOrder(db, identity);
        order.verify(db).update(anyString(), eq("operator"), eq("mock.user.reset.started"), eq(first.id()), eq(environmentId));
        order.verify(identity).deleteMockUser(env, first.id());
        order.verify(db).update(anyString(), eq("operator"), eq("mock.user.deleted"), eq(first.id()), eq(environmentId));
        order.verify(db).update(anyString(), eq("operator"), eq("mock.user.reset.started"), eq(second.id()), eq(environmentId));
        order.verify(identity).deleteMockUser(env, second.id());
        order.verify(db).update(anyString(), eq("operator"), eq("mock.user.reset.failed"), eq(second.id()), eq(environmentId));
        order.verify(db).update(anyString(), eq("operator"), eq("mock.reset.partial"), eq(environmentId), eq(environmentId));
        verify(manager, times(5)).getTransaction(argThat(def -> def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        clearInvocations(db, identity);
        assertThrows(ResponseStatusException.class, () -> service.resetMockUsers(environmentId,
            new MockController.Reset(List.of(first.id(), first.id()), plan.revision()), "operator"));
        verify(identity, never()).deleteMockUser(any(), any());
        verify(db, never()).update(anyString(), any(), any(), any(), any());
    }
}
