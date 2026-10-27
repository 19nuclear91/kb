-- =====================================================================
-- ParadeDB 语法与中文分词复验脚本
--
-- 用途：在你实际部署的 ParadeDB 版本上跑一遍，确认 V2 的 DDL 语法可用，
--       并直观看到 jieba 对你的业务术语切得对不对。
--
-- 用法： psql -h <host> -U <user> -d kb -f db/verify_paradedb.sql
-- =====================================================================

\echo
'=== 1. 扩展与版本 ==='
SELECT extname, extversion
FROM pg_extension
WHERE extname IN ('pg_search', 'vector');

\echo
''
\echo '=== 2. jieba 分词效果（换成你自己的业务术语再看一遍）==='
-- 索引侧：默认 search_mode=true，复合词会同时产出子词，利于召回
SELECT '企业智能知识库系统架构'::pdb.jieba::text[] AS "索引侧(search_mode=true)";
-- 查询侧：search_mode=false 只保留完整词，避免命中仅含部分复合词的文档
SELECT '企业智能知识库系统架构'::pdb.jieba('search_mode=false')::text[] AS "查询侧(search_mode=false)";

\echo
''
\echo '--- 对照：chinese_compatible 是按单字切分，通常不如 jieba ---'
SELECT '企业智能知识库系统架构'::pdb.chinese_compatible::text[] AS "chinese_compatible";

\echo
''
\echo '=== 3. 索引是否创建成功 ==='
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'kb_doc_chunk'
ORDER BY indexname;

\echo
''
\echo '=== 4. BM25 检索与评分（pdb.score，不是 paradedb.score）==='
SELECT id, doc_id, pdb.score(id) AS score, left (content, 40) AS snippet
FROM kb_doc_chunk
WHERE content ||| '微服务 架构'
ORDER BY pdb.score(id) DESC
    LIMIT 5;

\echo
''
\echo '=== 5. 全部词命中（&&&）与通用算子（@@@）==='
SELECT id, left (content, 40)
FROM kb_doc_chunk
WHERE content &&& '微服务 拆分' LIMIT 5;
SELECT id, left (content, 40)
FROM kb_doc_chunk
WHERE content @@@ pdb.match_disjunction('检索 融合') LIMIT 5;

\echo
''
\echo '=== 6. 权限过滤能否下推进索引（关注是否走 idx_chunk_fts）==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, pdb.score(id)
FROM kb_doc_chunk
WHERE content ||| '架构'
  AND space_id = ANY (ARRAY[1]::bigint[])
  AND security_level <= 1
ORDER BY pdb.score(id) DESC
    LIMIT 10;

\echo
''
\echo '=== 7. 向量索引执行计划（需先回填 embedding，否则无意义）==='
\echo '--- 若 embedding 全为 NULL，本段会返回空结果，属正常 ---'
EXPLAIN
SELECT id
FROM kb_doc_chunk
WHERE embedding IS NOT NULL
ORDER BY embedding <=> (SELECT embedding FROM kb_doc_chunk WHERE embedding IS NOT NULL LIMIT 1)
 LIMIT 10;
