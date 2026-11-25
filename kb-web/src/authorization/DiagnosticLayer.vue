<script setup lang="ts">
import type { Layer, Permission } from './api';
defineProps<{ layer: Layer; title: string }>();
const reasonLabels: Record<string, string> = { SUBJECT_BLOCKED: '账号当前不满足访问条件', SPACE_ARCHIVED: '空间已归档，资源访问暂停', CLEARANCE_BLOCKED: '密级条件未通过', AUTHORIZATION_NOT_GRANTED: '未命中授权', VIEW_NOT_GRANTED: '缺少查看授权', DOWNLOAD_NOT_GRANTED: '缺少下载授权' };
const grantLabel = (row: Permission) => ({ MATCHED: '授权命中', NOT_MATCHED: '未命中授权', NOT_EVALUATED: '授权未评估' })[row.grantMatch];
const conditionLabel = (row: Permission) => ({ PASSED: '通用条件通过', BLOCKED: '通用条件未通过', NOT_EVALUATED: '通用条件未评估' })[row.scopeCondition];
</script>
<template>
  <section :aria-label="title"><h4>{{ title }}</h4>
    <p v-if="layer.subjectCondition === 'BLOCKED'">账号当前不满足访问条件；授权来源和密级条件未评估。</p>
    <h5>空间授权</h5><ul><li v-for="row in layer.spacePermissions" :key="row.permission">{{ row.permission }}：{{ grantLabel(row) }}；{{ conditionLabel(row) }}<span v-for="reason in row.reasonCodes" :key="reason"> · {{ reasonLabels[reason] }}</span>
      <ul v-if="row.sourceKeys.length"><li v-for="key in row.sourceKeys" :key="key">{{ layer.sources.find(source => source.key === key)?.label }} · {{ layer.sources.find(source => source.key === key)?.principalType ?? '全局管理' }}</li></ul>
    </li></ul>
    <template v-if="layer.documentPermissions"><h5>所选文档授权</h5><p>manage 采用所属空间管理资格。edit、review、comment 仅解释授权与通用条件，具体操作仍需业务检查。</p>
      <ul><li v-for="row in layer.documentPermissions" :key="row.permission">{{ row.permission }}：{{ grantLabel(row) }}；{{ conditionLabel(row) }}<span v-for="reason in row.reasonCodes" :key="reason"> · {{ reasonLabels[reason] }}</span>
        <ul v-if="row.sourceKeys.length"><li v-for="key in row.sourceKeys" :key="key">{{ layer.sources.find(source => source.key === key)?.label }} · {{ layer.sources.find(source => source.key === key)?.sourceKind === 'DOCUMENT' ? '文档例外' : '空间授权' }}</li></ul>
      </li></ul>
      <h5>当前发布版本访问</h5>
      <p v-if="layer.currentVersionAccess.status === 'NO_CURRENT_PUBLISHED_VERSION'">没有当前发布版本，查看与下载未评估；这不表示所有历史或待审版本都不可访问。</p>
      <p v-else-if="layer.currentVersionAccess.status === 'NOT_EVALUATED'">当前发布版本访问未评估。</p>
      <template v-else><p>查看：{{ layer.currentVersionAccess.view === 'ALLOWED' ? '权限条件允许' : '权限条件不允许' }}<span v-for="reason in layer.currentVersionAccess.viewReasonCodes" :key="reason"> · {{ reasonLabels[reason] }}</span></p>
        <p>下载：{{ layer.currentVersionAccess.download === 'ALLOWED' ? '权限条件允许' : '权限条件不允许' }}<span v-for="reason in layer.currentVersionAccess.downloadReasonCodes" :key="reason"> · {{ reasonLabels[reason] }}</span></p></template>
    </template>
  </section>
</template>
