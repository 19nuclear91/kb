-- 测试只实现配额脚本使用的 Redis 命令；通过推进 clock 模拟键过期。
clock = 0
local values, expiry = {}, {}
redis = {}

function redis.call(op, key, a, b, c)
    if expiry[key] and expiry[key] <= clock then
        values[key], expiry[key] = nil, nil
    end
    local value = values[key]
    if op == 'GET' then
        return value or false
    elseif op == 'SET' then
        values[key] = tostring(a)
        expiry[key] = b == 'EX' and clock + tonumber(c) or nil
        return 'OK'
    elseif op == 'DEL' then
        values[key], expiry[key] = nil, nil
        return value and 1 or 0
    elseif op == 'EXISTS' then
        return value and 1 or 0
    elseif op == 'EXPIRE' then
        if value then expiry[key] = clock + tonumber(a) end
        return value and 1 or 0
    elseif op == 'INCR' or op == 'INCRBY' or op == 'DECRBY' then
        local delta = op == 'INCR' and 1 or tonumber(a)
        local total = tonumber(value or 0) + (op == 'DECRBY' and -delta or delta)
        values[key] = tostring(total)
        return total
    elseif op == 'HSET' or op == 'ZADD' then
        local field = op == 'HSET' and a or b
        local entry = op == 'HSET' and b or a
        value = value or {}
        local added = value[field] == nil
        value[field] = tostring(entry)
        values[key] = value
        return added and 1 or 0
    elseif op == 'HGET' or op == 'ZSCORE' then
        return value and value[a] or false
    elseif op == 'HDEL' or op == 'ZREM' then
        if not value or not value[a] then return 0 end
        value[a] = nil
        if not next(value) then values[key], expiry[key] = nil, nil end
        return 1
    elseif op == 'ZCARD' then
        local count = 0
        for _ in pairs(value or {}) do count = count + 1 end
        return count
    elseif op == 'ZRANGE' or op == 'ZREMRANGEBYSCORE' then
        assert(a == '-inf' and (op ~= 'ZRANGE' or c == 'BYSCORE'))
        local members = {}
        for member, score in pairs(value or {}) do
            if tonumber(score) <= tonumber(b) then table.insert(members, member) end
        end
        if op == 'ZRANGE' then return members end
        for _, member in ipairs(members) do value[member] = nil end
        if value and not next(value) then values[key], expiry[key] = nil, nil end
        return #members
    end
    error('未实现的 Redis 命令 ' .. op)
end
