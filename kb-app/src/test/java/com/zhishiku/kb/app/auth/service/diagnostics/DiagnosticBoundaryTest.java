package com.zhishiku.kb.app.auth.service.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.diagnostics.DiagnosticMapper;
import com.zhishiku.kb.app.auth.controller.diagnostics.DiagnosticAdvice;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews;
import com.zhishiku.kb.app.config.DiagnosticLimits;
import com.zhishiku.kb.app.config.DiagnosticStatementBudget;
import com.zhishiku.kb.app.auth.controller.diagnostics.DiagnosticController;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.app.config.DiagnosticResponseFilter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosticBoundaryTest {
    @Test
    void controllerDecodesOnlyAfterServiceAdmissionAndRejectsQueriesAndActualBodyOverflow() {
        var service = mock(DiagnosticService.class);
        var controller = new DiagnosticController(service, new DiagnosticLimits(16, 4096, 1000, 524288, 5000));
        when(service.diagnose(any(String.class), any(Long.class), any(Boolean.class), any())).thenAnswer(call -> {
            java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> body = call.getArgument(3);
            body.get();
            return null;
        });
        var query = new MockHttpServletRequest("POST", "/api/admin/spaces/10/authorization-diagnostics/explain");
        query.setQueryString("userId=8");
        assertThatThrownBy(() -> controller.explain("spaces", "10", query)).isInstanceOf(DiagnosticException.class);
        var oversized = new MockHttpServletRequest("POST", "/api/admin/spaces/10/authorization-diagnostics/explain") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        oversized.setContent(new byte[17]);
        assertThatThrownBy(() -> controller.explain("spaces", "10", oversized))
                .isInstanceOfSatisfying(DiagnosticException.class, error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.PAYLOAD_TOO_LARGE));
    }

    @Test
    void mapperResultProjectionPreservesIdsAndBooleanConditions() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DiagnosticMapper.xml");
        var args = new HashMap<String, Object>();
        args.put("spaceId", 10L);
        args.put("documentId", 20L);
        args.put("principalKeys", Set.of("1:8"));
        args.put("fetch", 1001);
        args.put("rows", List.of());
        var row = new LinkedHashMap<String, Object>();
        row.put("source_kind", "SPACE");
        row.put("principal_type", 1);
        row.put("principal_id", Long.MAX_VALUE);
        row.put("principal_key", "1:" + Long.MAX_VALUE);
        row.put("label", "显示名");
        row.put("permission", "view");
        row.put("proposed", false);
        var source = MapperRowStub.readOne(configuration, DiagnosticMapper.class.getName() + ".sources", args, row, DiagnosticFacts.Acl.class);
        assertThat(source.principalId()).isEqualTo(Long.MAX_VALUE);
        var currentRow = new LinkedHashMap<String, Object>();
        currentRow.put("has_current", true);
        currentRow.put("valid_current", true);
        currentRow.put("clearance_passed", false);
        currentRow.put("view_allowed", false);
        currentRow.put("download_allowed", false);
        var current = MapperRowStub.readOne(configuration, DiagnosticMapper.class.getName() + ".current", args, currentRow, DiagnosticFacts.Current.class);
        assertThat(current).isEqualTo(new DiagnosticFacts.Current(true, true, false, false, false));
    }

    @Test
    void statementUsesRemainingBudgetAndClosesSettingsWithoutWrites() throws Throwable {
        var connection = mock(java.sql.Connection.class);
        var settings = mock(java.sql.PreparedStatement.class);
        var query = mock(java.sql.PreparedStatement.class);
        var handler = mock(StatementHandler.class);
        when(connection.prepareStatement(any(String.class))).thenReturn(settings);
        when(handler.prepare(connection, 5)).thenReturn(query);
        var clock = new AtomicLong();
        try (var budget = new DiagnosticBudget(5000, clock::get)) {
            clock.set(3_000_000_000L);
            var invocation = new Invocation(handler, StatementHandler.class.getMethod("prepare", java.sql.Connection.class, Integer.class), new Object[]{connection, 5});
            assertThat(new DiagnosticStatementBudget().intercept(invocation)).isSameAs(query);
            verify(settings).setString(1, "2000");
            verify(settings).setString(2, "2000");
            verify(settings).execute();
            verify(settings).close();
            verify(query).setQueryTimeout(2);
        }
    }

    @Test
    void sharedMapperCompilesAndBindsExactIdsWithoutDatabaseOrSqlTextAssertions() throws Exception {
        Configuration configuration = new Configuration();
        for (String resource : List.of("mapper/AuthorizationRules.xml", "mapper/PermissionMapper.xml", "mapper/DiagnosticMapper.xml", "mapper/DocumentMapper.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var args = new HashMap<String, Object>();
        args.put("spaceId", Long.MAX_VALUE);
        args.put("documentId", 20L);
        args.put("clearance", (short) 2);
        args.put("principalKeys", Set.of("1:8", "2:3", "3:5"));
        args.put("rows", List.of(new DiagnosticFacts.Acl("SPACE", 1, 8, "1:8", "名字", "view", false)));
        args.put("globalManage", false);
        args.put("fetch", 1001);
        for (String method : List.of("sources", "grants", "current")) {
            var statement = configuration.getMappedStatement(DiagnosticMapper.class.getName() + "." + method);
            var bound = statement.getBoundSql(args);
            assertThat(bound.getParameterMappings()).isNotEmpty();
            assertThat(bound.getParameterObject()).isSameAs(args);
        }
    }

    @Test
    void transactionIsIndependentReadOnlyAndEncodingIsAtomic() throws Exception {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return status;
        });
        var database = new DiagnosticDatabase(manager, DiagnosticLimits.defaults(), new ObjectMapper());
        var response = database.response(() -> new DiagnosticViews.User(Long.toString(Long.MAX_VALUE), "<script>显示名</script>"));
        assertThat(response.data().userId()).isEqualTo(Long.toString(Long.MAX_VALUE));
        verify(manager).commit(status);
        var limited = new DiagnosticDatabase(manager, new DiagnosticLimits(100, 10, 10, 1, 5000), new ObjectMapper());
        assertThatThrownBy(() -> limited.response(() -> "超限"))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED));
        verify(manager).rollback(status);
        assertThat(DiagnosticBudget.current()).isNull();
    }

    @Test
    void controlledDeadlineAndCancellationFailWithoutPartialResult() {
        AtomicLong clock = new AtomicLong();
        try (var budget = new DiagnosticBudget(5, clock::get)) {
            assertThat(budget.remainingMillis()).isEqualTo(5);
            clock.set(5_000_000L);
            assertThatThrownBy(budget::remainingMillis).isInstanceOf(DiagnosticException.class);
        }
        try (var budget = new DiagnosticBudget(5, clock::get)) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(budget::remainingMillis).isInstanceOf(DiagnosticException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void privatePathKeepsNoStoreAcrossResetAndUnknownFailureHidesException() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/admin/documents/20/authorization-diagnostics/unknown");
        var response = new MockHttpServletResponse();
        new DiagnosticResponseFilter().doFilter(request, response, (incoming, outgoing) -> {
            var http = (jakarta.servlet.http.HttpServletResponse) outgoing;
            http.reset();
            http.setHeader("Cache-Control", "public");
            http.setStatus(404);
        });
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var failure = DiagnosticAdvice.project(new IllegalStateException("secret target and sql"));
        assertThat(failure.getStatusCode().value()).isEqualTo(503);
        assertThat(failure.getBody().reasonCode()).isEqualTo("DIAGNOSTIC_UNAVAILABLE");
        assertThat(failure.getBody().message()).doesNotContain("secret");
    }
}
