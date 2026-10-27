-- =====================================================================
-- 权限边界验证脚本
--
-- 这是整个 P0 里最该反复跑的东西。决策记录 4.5 要求：
-- 检索评测必须同时覆盖"不能越权"和"过滤后仍能找到足够结果"。
--
-- 每个用例都有明确预期，跑完逐条对照。
-- 用法： psql -h <host> -U kb -d kb -f db/verify_permission.sql
-- =====================================================================

-- 与 mapper/SearchMapper.xml 的 aclPredicate 保持同一套逻辑，
-- 避免出现"脚本通过但代码不通过"的假安全感。
CREATE
OR REPLACE FUNCTION dev_visible_docs(
    p_space_ids      BIGINT[],
    p_principal_keys TEXT[],
    p_clearance      SMALLINT
) RETURNS TABLE (doc_id BIGINT, title TEXT, sec SMALLINT) AS $$
SELECT DISTINCT d.id, d.title, c.security_level
FROM kb_doc_chunk c
         JOIN kb_document d ON d.id = c.doc_id
WHERE d.status = 'published'
  AND c.security_level <= p_clearance
  AND (c.space_id = ANY (p_space_ids)
    OR EXISTS (SELECT 1
               FROM kb_document_acl a
               WHERE a.doc_id = c.doc_id
                 AND a.permission = 'view'
                 AND a.principal_key = ANY (p_principal_keys)))
ORDER BY d.id;
$$
LANGUAGE sql STABLE;

\echo
''
\echo '########## 用例 1：alice（平台组 dept=4，clearance=2）##########'
\echo '预期：文档 1,2,3（技术空间，密级 0/1/2）'
\echo '      文档4 虽有文档级例外授权，但密级3 > clearance2 → 不可见（密级是"与"关系）'
\echo '      文档5 status=draft → 不可见'
SELECT *
FROM dev_visible_docs(
        ARRAY[1]::bigint[], -- 技术空间（经部门2的授权继承）
        ARRAY['1:1', '3:4', '3:2', '3:1']::text[], -- alice本人 / 平台组 / 研发中心 / 总公司
        2::smallint);

\echo
''
\echo '########## 用例 2：bob（算法组 dept=5，clearance=1）##########'
\echo '预期：仅文档 1,2。文档3 密级2 超出其 clearance，即便是他本人创建也不可见'
SELECT *
FROM dev_visible_docs(
        ARRAY[1]::bigint[],
        ARRAY['1:2', '3:5', '3:2', '3:1']::text[],
        1::smallint);

\echo
''
\echo '########## 用例 3：carol（财务部 dept=3，clearance=3，审计员角色）##########'
\echo '预期：仅文档4。技术空间未授权给财务部，文档1,2,3 不可见'
SELECT *
FROM dev_visible_docs(
        ARRAY[2]::bigint[],
        ARRAY['1:3', '3:3', '3:1', '2:2']::text[],
        3::smallint);

\echo
''
\echo '########## 用例 4：dave（总公司 dept=1，clearance=0）##########'
\echo '预期：空结果。授权挂在"研发中心"(2)，不会向上传递到总公司(1)。'
\echo '      本用例确认部门继承方向是"向下"而非"向上"。'
SELECT *
FROM dev_visible_docs(
        ARRAY[]::bigint[],
        ARRAY['1:4', '3:1']::text[],
        0::smallint);

\echo
''
\echo '########## 用例 5：越权探测 —— 无任何主体、无任何空间 ##########'
\echo '预期：空结果。绝不能因为参数为空而退化成"不过滤"。'
SELECT *
FROM dev_visible_docs(
        ARRAY[]::bigint[],
        ARRAY[]::text[],
        3::smallint);

\echo
''
\echo '########## 用例 6：文档级例外确实能扩权 ##########'
\echo '预期：把 alice 的 clearance 提到3 后，财务文档4 应通过文档级例外可见——'
\echo '      她既不在财务部，财务空间也没授权给她，靠的纯粹是 kb_document_acl 那条记录'
SELECT *
FROM dev_visible_docs(
        ARRAY[1]::bigint[],
        ARRAY['1:1', '3:4', '3:2', '3:1']::text[],
        3::smallint);

\echo
''
\echo '########## 用例 7：文档例外无法"收紧"（首期已知边界）##########'
\echo '预期：文档1 仍可见。首期不做 deny，因此无法表达"空间可见但此文档更严"。'
\echo '      收紧需要显式拒绝语义，列入二期。见决策记录 4.4。'
SELECT *
FROM dev_visible_docs(
        ARRAY[1]::bigint[],
        ARRAY['1:4']::text[], -- dave 本人，无部门授权
        3::smallint);

-- =====================================================================
-- 归档与 manage 权限位（空间授权管理）
--
-- 与 PermissionMapper.findVisibleSpaceIds 保持同一套逻辑。归档过滤只落在
-- 这一处，所有读路径都经由它取得可见空间集合。
-- =====================================================================
CREATE
OR REPLACE FUNCTION dev_visible_spaces(
    p_principal_keys TEXT[],
    p_permission     TEXT
) RETURNS TABLE (space_id BIGINT, code TEXT, archived BOOLEAN) AS $$
SELECT DISTINCT a.space_id, s.code, s.archived
FROM kb_space_acl a
         JOIN kb_space s ON s.id = a.space_id
WHERE a.permission = p_permission
  AND (s.archived = false OR a.permission = 'manage')
  AND a.principal_key = ANY (p_principal_keys)
ORDER BY 1;
$$
LANGUAGE sql STABLE;

-- 用例 8-10 需要改数据（授予 manage、归档空间），整段放在事务里并回滚，
-- 保证本脚本可以反复执行而不污染目标库。
BEGIN;

INSERT INTO kb_space_acl (space_id, principal_type, principal_id, permission)
VALUES (1, 1, 1, 'manage') -- 把技术空间的 manage 授予 alice
    ON CONFLICT (space_id, principal_type, principal_id, permission) DO NOTHING;

\echo
''
\echo '########## 用例 8：未归档时，读权限位与 manage 都能看到空间 ##########'
\echo '预期：view 返回空间1；manage 返回空间1。'
SELECT 'view' AS permission, *
FROM dev_visible_spaces(ARRAY['3:2']::text[], 'view')
UNION ALL
SELECT 'manage', *
FROM dev_visible_spaces(ARRAY['1:1']::text[], 'manage');

UPDATE kb_space
SET archived = true
WHERE id = 1;

\echo
''
\echo '########## 用例 9：归档后空间退出读权限位可见范围 ##########'
\echo '预期：空结果。归档即意味着其文档不再参与检索、列表与下载。'
\echo '      本用例守的是"归档不是装饰"——过滤必须真的生效。'
SELECT *
FROM dev_visible_spaces(ARRAY['3:2']::text[], 'view');

\echo
''
\echo '########## 用例 10：归档后 manage 仍可见 ##########'
\echo '预期：仍返回空间1（archived=true）。这是刻意的例外——'
\echo '      若 manage 也被过滤掉，空间一经归档，被委派的管理员连'
\echo '      "取消归档"都无法执行，会永久失去该空间。'
SELECT *
FROM dev_visible_spaces(ARRAY['1:1']::text[], 'manage');

ROLLBACK;

\echo
''
\echo '########## 用例 11：归档过滤不得被空主体集绕过 ##########'
\echo '预期：空结果。与用例 5 同理，参数为空绝不能退化成"不过滤"。'
SELECT *
FROM dev_visible_spaces(ARRAY[]::text[], 'manage');
