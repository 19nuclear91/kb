package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.SecurityLevelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 密级取值的唯一判定来源。
 *
 * <h2>为什么要有这一层，而不是在各处写区间校验</h2>
 *
 * <p>"密级分几级、与用户 clearance 如何对应"至今未由业务确认（见《架构决策记录.md》
 * 第 10 节"仍待收集"第 5 项）。只要校验逻辑里出现 {@code 0..3} 这样的上下界，
 * 答案到位的那天就得去翻出每一处硬编码——而漏掉一处的后果是不对称的：
 * 上界漏小了只是新级别用不了，漏大了则是接受一个字典里不存在的密级。
 *
 * <p>把取值集合收到 {@code sys_security_level} 一张表里，级数就退化成数据问题：
 * 增删行即生效，不改代码。这也是本能力声称"对级数不敏感"的全部依据。
 *
 * <h2>为什么不抛异常</h2>
 *
 * <p>调用方分属两个错误族：身份路径抛 {@link com.zhishiku.kb.common.security.IdentityException}
 * （带 {@code IdentityErrorCode}），上传路径抛 {@link com.zhishiku.kb.common.api.ApiException}。
 * 在这里抛任何一种，都会让另一条路径的响应错误码形状被这一次校验破例。
 * 所以这里只回答"在不在字典里"，由调用方用自己的错误族拒绝。
 *
 * <h2>为什么不缓存</h2>
 *
 * <p>与 {@code PermissionService} 的取舍一致：首期不加缓存。字典只在写路径
 * （密级调整、文档上传）上被读一次，不在检索热路径上；而加了缓存就得回答
 * "改表后多久生效"——那是个会让"增删行即生效"这句话失效的问题。
 */
@Service
public class SecurityLevelDictionary {

    private final SecurityLevelMapper mapper;

    public SecurityLevelDictionary(SecurityLevelMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 全部可用密级，升序。
     */
    @Transactional(readOnly = true)
    public List<Short> availableLevels() {
        List<Short> levels = mapper.listLevels();
        return levels == null ? List.of() : levels;
    }

    /**
     * 目标密级是否在字典中。
     *
     * <p>字典为空时一律返回 {@code false}。那是"数据缺失"而不是"任意取值均可"
     * ——空字典下放行等于在建表失败或迁移未执行时静默接受任何密级，
     * 而外键那道兜底在这种情形下同样不会命中。
     */
    @Transactional(readOnly = true)
    public boolean contains(Short level) {
        return level != null && availableLevels().contains(level);
    }

    /**
     * 越界密级的拒绝说明，含可用级别清单。
     *
     * <p>清单是服务端独有的知识，不列出来调用方只能靠猜或读文档，而文档正是
     * 会过期的那一份。消息在这里统一生成，两条调用路径的措辞才不会分叉。
     */
    public String rejectionMessage(String field, Short level) {
        String available = availableLevels().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return field + " " + level + " 不是可用密级；当前可用密级："
                + (available.isEmpty() ? "（密级字典为空）" : available);
    }
}
