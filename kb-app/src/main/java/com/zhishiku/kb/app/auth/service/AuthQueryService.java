package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 当前用户自助读取，不提升身份管理或内容访问资格。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class AuthQueryService {
    private final AdminQueryAccess access;
    private final SecurityLevelDictionary levels;

    public AuthQueryService(AdminQueryAccess access, SecurityLevelDictionary levels) {
        this.access = access;
        this.levels = levels;
    }

    public List<String> permissions(Map<String, List<String>> input) {
        var handle = access.current();
        new QueryParameters(input);
        return handle.functionalPermissions().stream().sorted().toList();
    }

    public List<Short> levels(Map<String, List<String>> input) {
        access.current();
        new QueryParameters(input);
        return levels.availableLevels().stream().distinct().sorted().toList();
    }
}
