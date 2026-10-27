package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.SecurityLevelMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户密级调整的五种结果。
 *
 * <p>全部用 stub mapper 与 stub 字典数据源，不连库。字典本体用真对象、只 stub 它
 * 背后的查询结果：被替换的仅是"字典里有哪些级别"这个数据事实——级数未由业务确认，
 * 正是不该写进测试的东西——而取值判定与拒绝措辞仍是被测的真实代码。
 */
class IdentityServiceClearanceTest {

    private static final short SECRET = 2;

    private IdentityDepartmentMapper mapper;
    private IdentityAuditService audit;
    private SecurityLevelMapper levelMapper;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        audit = mock(IdentityAuditService.class);
        levelMapper = mock(SecurityLevelMapper.class);
        when(levelMapper.listLevels())
                .thenReturn(List.of((short) 0, (short) 1, (short) 2, (short) 3));
        service = new IdentityService(mapper, null, audit,
                new SecurityLevelDictionary(levelMapper));
    }

    @Test
    @DisplayName("字典内取值：更新密级，返回新密级与新版本，审计记录前后密级")
    void acceptsDictionaryLevelAndAuditsBeforeAndAfter() {
        when(mapper.findIdentityForUpdate(7L)).thenReturn(user(7L, (short) 0, 4L));
        when(mapper.updateUserClearance(7L, SECRET, 4L)).thenReturn(1);
        when(mapper.findIdentity(7L)).thenReturn(user(7L, SECRET, 5L));

        IdentitySummary result = service.changeClearance(7L, SECRET, 4L, 1L, "req-1");

        assertThat(result.clearanceLevel()).isEqualTo(SECRET);
        assertThat(result.version()).as("调整后必须返回新版本，否则调用方无法继续写").isEqualTo(5L);
        verify(mapper).updateUserClearance(7L, SECRET, 4L);

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).success(eq(AuditScope.USER_CLEARANCE_CHANGE), eq("req-1"), eq(1L), eq(7L),
                anyString(), before.capture(), after.capture());
        assertThat(before.getValue())
                .as("审计必须读得出调整前的密级，否则只剩\"某人改了某人\"")
                .contains("\"clearanceLevel\":0");
        assertThat(after.getValue())
                .as("审计必须读得出调整后的密级")
                .contains("\"clearanceLevel\":2");
    }

    @Test
    @DisplayName("字典外取值：返回取值错误，密级不变")
    void rejectsLevelOutsideDictionary() {
        assertThatThrownBy(() -> service.changeClearance(7L, (short) 9, 4L, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(400))
                .hasMessageContaining("9")
                .as("错误信息必须列出可用级别——可用集合是服务端独有的知识")
                .hasMessageContaining("0, 1, 2, 3");

        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
        verify(mapper, never()).findIdentityForUpdate(anyLong());
    }

    /**
     * 空字典必须拒绝，而不是退化成"任意取值均可"。
     *
     * <p>这是建表失败或迁移未执行时的形态。那时外键那道兜底同样不会命中，
     * 放行就等于在系统最脆弱的时刻接受任何密级。
     */
    @Test
    @DisplayName("字典为空时一律拒绝，不得退化为放行")
    void emptyDictionaryRejectsEveryLevel() {
        when(levelMapper.listLevels()).thenReturn(List.of());

        assertThatThrownBy(() -> service.changeClearance(7L, (short) 0, 4L, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("密级字典为空");
        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
    }

    @Test
    @DisplayName("以自身为目标：返回禁止错误，密级不变")
    void rejectsSelfAsTarget() {
        assertThatThrownBy(() -> service.changeClearance(7L, SECRET, 4L, 7L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(403))
                .hasMessageContaining("自己");

        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
    }

    /**
     * 自我判定必须早于字典校验。
     *
     * <p>否则"给自己设一个字典外的密级"会先撞 400，泄露出"这个级别不可用"
     * 而不是"你不能改自己"——拒绝理由错位会把一次越权尝试误导成一次参数问题。
     */
    @Test
    @DisplayName("以自身为目标时先判自我提权，不先报取值错误")
    void selfTargetIsRejectedBeforeDictionaryCheck() {
        assertThatThrownBy(() -> service.changeClearance(7L, (short) 9, 4L, 7L, "req-1"))
                .hasMessageContaining("自己");
    }

    @Test
    @DisplayName("提交的版本已过期：返回并发冲突，密级不变")
    void staleVersionReturnsConflict() {
        when(mapper.findIdentityForUpdate(7L)).thenReturn(user(7L, (short) 0, 9L));

        assertThatThrownBy(() -> service.changeClearance(7L, SECRET, 4L, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> {
                    IdentityException identity = (IdentityException) e;
                    assertThat(identity.httpStatus()).isEqualTo(409);
                    assertThat(identity.errorCode()).isEqualTo(IdentityErrorCode.INVALID_VERSION);
                });

        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
    }

    /**
     * 比较之后、更新之前被别人抢先，同样要变成并发冲突。
     *
     * <p>{@code findIdentityForUpdate} 与 {@code updateUserClearance} 之间存在一个窗口。
     * 少了这条行数判定，抢先的那次降级会被静默覆盖，而两条审计都显示成功。
     */
    @Test
    @DisplayName("更新影响 0 行也要返回并发冲突，不得当成成功")
    void zeroUpdatedRowsReturnsConflict() {
        when(mapper.findIdentityForUpdate(7L)).thenReturn(user(7L, (short) 0, 4L));
        when(mapper.updateUserClearance(7L, SECRET, 4L)).thenReturn(0);

        assertThatThrownBy(() -> service.changeClearance(7L, SECRET, 4L, 1L, "req-1"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).errorCode())
                        .isEqualTo(IdentityErrorCode.INVALID_VERSION));

        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("同一 requestId 重放：返回首次结果，不再写一次")
    void replayedRequestIdReturnsFirstResult() {
        when(audit.existing(AuditScope.USER_CLEARANCE_CHANGE, "req-1"))
                .thenReturn(auditEvent("req-1"));
        when(mapper.findIdentity(7L)).thenReturn(user(7L, SECRET, 5L));

        IdentitySummary result = service.changeClearance(7L, SECRET, 4L, 1L, "req-1");

        assertThat(result.clearanceLevel()).isEqualTo(SECRET);
        assertThat(result.version()).isEqualTo(5L);
        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
    }

    /**
     * 缺请求标识必须在落库之前被拒。
     *
     * <p>密级调整没有"不带 requestId"的重载，因为没有审计的调整就是一次查不出
     * 操作者的扩权。这条断言同时钉住"不进入持久化"：mapper 一次都没被碰到。
     */
    @Test
    @DisplayName("缺请求标识：返回取值错误，不进入持久化")
    void missingRequestIdIsRejectedBeforePersisting() {
        assertThatThrownBy(() -> service.changeClearance(7L, SECRET, 4L, 1L, null))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).httpStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.changeClearance(7L, SECRET, 4L, 1L, "   "))
                .isInstanceOf(IdentityException.class);

        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
        verify(mapper, never()).findIdentityForUpdate(anyLong());
    }

    /**
     * 缺字典或缺审计时必须炸掉，不得退化成静默放行。
     *
     * <p>同类写操作对缺失的 audit 是宽容的（{@code auditSuccess} 悄悄跳过）。
     * 密级调整照抄那份宽容的后果，与本变更修复的角色接线缺陷同形：
     * 一次扩权没有留下操作者，且测试全绿。
     */
    @Test
    @DisplayName("用简化构造器时密级调整直接失败，不会跳过校验或审计")
    void refusesToRunWithoutDictionaryOrAudit() {
        assertThatThrownBy(() -> new IdentityService(mapper)
                .changeClearance(7L, SECRET, 4L, 1L, "req-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdentityService(mapper, null, audit)
                .changeClearance(7L, SECRET, 4L, 1L, "req-1"))
                .isInstanceOf(IllegalStateException.class);

        verify(mapper, never()).updateUserClearance(anyLong(), anyShort(), anyLong());
    }

    private static IdentityRecord user(long id, short clearance, long version) {
        return new IdentityRecord(id, "user-" + id, "User", 2L, clearance, true, version, null, null);
    }

    private static IdentityAuditRecord auditEvent(String requestId) {
        return new IdentityAuditRecord(null, null, AuditScope.USER_CLEARANCE_CHANGE.name(),
                requestId, 1L, "user", 7L, "change_clearance", "SUCCESS", "{}", "{}", null, null);
    }
}
