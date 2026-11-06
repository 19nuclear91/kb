package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleQueryMapper;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true, timeout = 5)
public class LifecycleQueryService {
    private static final Set<String> STATUSES = Set.of("OFFBOARDED", "UNMAPPED", "REVIEW", "FAILED", "IGNORED", "CONFLICT");
    private final LifecycleAuthorizer auth;
    private final IdentityDepartmentMapper users;
    private final LifecycleMapper mappings;
    private final LifecycleInboxMapper inbox;
    private final LifecycleQueryMapper queries;
    private final DingTalkLifecycleSettings settings;
    private final ManagementInvariant management;
    private final DingTalkStreamConnection connection;

    public LifecycleQueryService(LifecycleAuthorizer auth, IdentityDepartmentMapper users, LifecycleMapper mappings,
                                  LifecycleInboxMapper inbox, LifecycleQueryMapper queries, DingTalkLifecycleSettings settings,
                                  DingTalkStreamConnection connection) {
        this.auth = auth;
        this.users = users;
        this.mappings = mappings;
        this.inbox = inbox;
        this.queries = queries;
        this.settings = settings;
        this.connection = connection;
        management = new ManagementInvariant(users);
    }

    public LifecycleViews.Status status(String token) {
        auth.require(token, Permission.IDENTITY_LIFECYCLE_READ);
        var counts = queries.counts();
        if (counts == null) {
            throw LifecycleFailures.unavailable();
        }
        return new LifecycleViews.Status(settings.enabled(), settings.clientSecret() != null && !settings.clientSecret().isBlank(),
                settings.connectionId(), connection.connected(), connection.lastConnectedAt(),
                management.emergencyRemainsAfter(ManagementChange.unchanged()), counts);
    }

    public LifecycleViews.User user(String token, String rawId) {
        auth.require(token, Permission.IDENTITY_LIFECYCLE_READ);
        long id = QueryParameters.positiveValue(rawId);
        var user = users.findIdentity(id);
        if (user == null) {
            throw QueryException.missing();
        }
        IdentityAccessState access;
        try {
            access = IdentityAccessState.from(user);
            if (user.getVersion() == null) {
                throw LifecycleFailures.unavailable();
            }
        } catch (RuntimeException inconsistent) {
            throw LifecycleFailures.unavailable();
        }
        var assignment = mappings.findAssignment(id);
        if ((assignment == null && access.restriction() != LifecycleAssignment.Restriction.CLEAR)
                || (assignment != null && (assignment.kind() == null || assignment.restriction() != access.restriction()))) {
            throw LifecycleFailures.unavailable();
        }
        return new LifecycleViews.User(id, user.getVersion(), assignment == null ? -1 : assignment.version(),
                access.manualEnabled(), access.enabled(), assignment == null ? LifecycleAssignment.Kind.NONE : assignment.kind(),
                access.restriction(), assignment == null ? null : assignment.connectionId(), assignment == null ? null : assignment.lastEventId(),
                assignment == null ? user.getUpdatedAt() : assignment.updatedAt(), assignment == null ? null : assignment.restoredAt());
    }

    public QueryPage<LifecycleViews.Event> events(String token, Map<String, List<String>> input) {
        auth.require(token, Permission.IDENTITY_LIFECYCLE_READ);
        var parameters = new QueryParameters(input, "status", "limit", "cursor");
        String status = parameters.exactText("status", 16);
        if (status != null && !STATUSES.contains(status)) {
            throw QueryException.invalid();
        }
        var filters = QueryParameters.filters("status", status);
        var cursor = cursor(parameters, "lifecycle-events", filters);
        int limit = parameters.limit();
        return QueryPage.slice(queries.events(status, cursor, limit + 1), limit,
                event -> QueryCursor.encode("lifecycle-events", filters, event.id()));
    }

    public QueryPage<LifecycleViews.Item> items(String token, String rawEvent, Map<String, List<String>> input) {
        auth.require(token, Permission.IDENTITY_LIFECYCLE_READ);
        long id = QueryParameters.positiveValue(rawEvent);
        var parameters = new QueryParameters(input, "limit", "cursor");
        var filters = QueryParameters.filters("eventId", id);
        var cursor = cursor(parameters, "lifecycle-items", filters);
        if (inbox.findEventById(id) == null) {
            throw QueryException.missing();
        }
        int limit = parameters.limit();
        return QueryPage.slice(queries.items(id, cursor, limit + 1), limit,
                item -> QueryCursor.encode("lifecycle-items", filters, item.id()));
    }

    public QueryPage<LifecycleViews.Emergency> emergency(String token, Map<String, List<String>> input) {
        auth.require(token, Permission.IDENTITY_LIFECYCLE_READ);
        var parameters = new QueryParameters(input, "limit", "cursor");
        var filters = Map.<String, Object>of();
        var cursor = cursor(parameters, "lifecycle-emergency", filters);
        int limit = parameters.limit();
        var ids = queries.emergencyUsers(cursor, limit + 1);
        var rows = ids.stream().map(id -> {
            var user = users.findIdentity(id);
            var assignment = mappings.findAssignment(id);
            var fact = users.findEmergencyCandidate(id);
            if (user == null || assignment == null || assignment.kind() != LifecycleAssignment.Kind.EMERGENCY || fact == null) {
                throw LifecycleFailures.unavailable();
            }
            var permissions = users.findUserManagementPermissions(id).stream().filter(row -> java.util.Objects.equals(row.userId(), id))
                    .map(row -> row.permission()).collect(Collectors.toSet());
            return new LifecycleViews.Emergency(id, user.getDisplayName(), user.getVersion(), assignment.version(),
                    fact.eligible() && permissions.containsAll(ManagementInvariant.EMERGENCY_PERMISSIONS));
        }).toList();
        return QueryPage.slice(rows, limit, row -> QueryCursor.encode("lifecycle-emergency", filters, row.userId()));
    }

    private static Long cursor(QueryParameters parameters, String kind, Map<String, Object> filters) {
        var cursor = QueryCursor.decode(parameters.cursor(), kind);
        if (cursor == null) {
            return null;
        }
        cursor.verify(kind, filters);
        return cursor.id();
    }
}
