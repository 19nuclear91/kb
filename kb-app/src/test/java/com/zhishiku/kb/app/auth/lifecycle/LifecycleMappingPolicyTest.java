package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LifecycleMappingPolicyTest {
    private final MemoryAssignments repository = new MemoryAssignments();
    private final LifecycleMappingPolicy policy = new LifecycleMappingPolicy(repository);

    @Test
    void registrationAndMappingRecheckTheWinnerInBothInterleavings() {
        policy.requireEmergencyCandidate(1);
        policy.requireAssignable(1, "dingtalk", "corp", "employee");
        repository.rows.put(1L, assignment(1, LifecycleAssignment.Kind.EMERGENCY, LifecycleAssignment.Restriction.CLEAR));
        assertThat(catchThrowableOfType(() -> policy.requireAssignable(1, "dingtalk", "corp", "employee"),
                IdentityException.class).httpStatus()).isEqualTo(409);
        repository.rows.clear();
        policy.requireEmergencyCandidate(1);
        policy.requireAssignable(1, "dingtalk", "corp", "employee");
        repository.rows.put(1L, assignment(1, LifecycleAssignment.Kind.MANAGED, LifecycleAssignment.Restriction.CLEAR));
        assertThat(catchThrowableOfType(() -> policy.requireEmergencyCandidate(1), IdentityException.class).httpStatus())
                .isEqualTo(409);
    }

    @Test
    void rejectsEmployeeAlreadyOwnedByAnotherUser() {
        repository.rows.put(1L, assignment(1L, LifecycleAssignment.Kind.MANAGED,
                LifecycleAssignment.Restriction.CLEAR));
        IdentityException error = catchThrowableOfType(
                () -> policy.requireAssignable(2L, "dingtalk", "corp", "employee"), IdentityException.class);
        assertThat(error.reason()).contains(IdentityConflictReason.IDENTIFIER_CONFLICT);
        assertThat(repository.rows).hasSize(1);
    }

    @Test
    void permitsUnmanagedLocalAccountAndSameOwner() {
        assertThatCode(() -> policy.requireAssignable(1L, "dingtalk", "corp", "employee"))
                .doesNotThrowAnyException();
        repository.rows.put(1L, assignment(1L, LifecycleAssignment.Kind.MANAGED,
                LifecycleAssignment.Restriction.CLEAR));
        assertThatCode(() -> policy.requireAssignable(1L, "dingtalk", "corp", "employee"))
                .doesNotThrowAnyException();
    }

    @Test
    void emergencyAndManagedIdentitiesAreMutuallyExclusive() {
        repository.rows.put(1L, assignment(1L, LifecycleAssignment.Kind.EMERGENCY,
                LifecycleAssignment.Restriction.CLEAR));
        IdentityException mapping = catchThrowableOfType(
                () -> policy.requireAssignable(1L, "dingtalk", "corp", "employee"), IdentityException.class);
        assertThat(mapping.reason()).contains(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED);
        repository.rows.put(1L, assignment(1L, LifecycleAssignment.Kind.MANAGED,
                LifecycleAssignment.Restriction.CLEAR));
        IdentityException emergency = catchThrowableOfType(
                () -> policy.requireEmergencyCandidate(1L), IdentityException.class);
        assertThat(emergency.reason()).contains(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED);
    }

    @Test
    void restrictionPreventsRebindingEvenToAnotherEmployee() {
        repository.rows.put(1L, assignment(1L, LifecycleAssignment.Kind.MANAGED,
                LifecycleAssignment.Restriction.OFFBOARDED));
        IdentityException error = catchThrowableOfType(
                () -> policy.requireAssignable(1L, "dingtalk", "corp", "another"), IdentityException.class);
        assertThat(error.reason()).contains(IdentityConflictReason.LIFECYCLE_RESTRICTED);
    }

    private static LifecycleAssignment assignment(long userId, LifecycleAssignment.Kind kind,
                                                 LifecycleAssignment.Restriction restriction) {
        return new LifecycleAssignment(userId, kind, "dingtalk", "corp", "employee", "connection",
                restriction, 0L, null, null, 9L, null, null);
    }

    private static final class MemoryAssignments implements LifecycleAssignments {
        private final Map<Long, LifecycleAssignment> rows = new HashMap<>();

        @Override
        public LifecycleAssignment findAssignment(long userId) {
            return rows.get(userId);
        }

        @Override
        public LifecycleAssignment findEmployee(String source, String enterpriseId, String employeeId) {
            return rows.values().stream()
                    .filter(row -> row.kind() == LifecycleAssignment.Kind.MANAGED)
                    .filter(row -> Objects.equals(row.source(), source))
                    .filter(row -> Objects.equals(row.enterpriseId(), enterpriseId))
                    .filter(row -> Objects.equals(row.employeeId(), employeeId))
                    .findFirst().orElse(null);
        }
    }
}
