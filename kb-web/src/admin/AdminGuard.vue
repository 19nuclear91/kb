<script setup lang="ts">
import { computed } from 'vue';
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import PageHeader from '../ui/PageHeader.vue';
import { canOpenSpaces, useAdminAccess, type AdminAccess } from './access';

/**
 * 管理页面守卫：按完整权限 code 精确判断，不依据角色名称、前缀或通配；进入时核对一次本人权限。
 * `spaces` 以可管理空间目录为开放条件；`allow` 接受依赖目录的自定义谓词（目录未就绪时等待，目录故障且谓词不成立时提示重读）。
 */
const props = withDefaults(defineProps<{ code?: string; title: string; spaces?: boolean; allow?: (access: AdminAccess) => boolean }>(), { code: undefined, spaces: false, allow: undefined });
const access = useAdminAccess();
access.check();
const gate = computed(() => props.allow ?? (props.spaces ? canOpenSpaces : undefined));
const view = computed(() => {
  const open = gate.value;
  if (access.status === 'loading' || (open && access.spaceStatus === 'loading')) {
    return 'loading';
  }
  if (access.status === 'unavailable' && (!open || !open(access))) {
    return 'unavailable';
  }
  if (open && !open(access) && access.spaceStatus === 'unavailable') {
    return 'spaces';
  }
  if ((props.code && !access.has(props.code)) || (open && !open(access))) {
    return 'denied';
  }
  return 'allowed';
});
</script>

<template>
  <div v-if="view === 'loading'" class="panel" role="status"><KbSpin /> <span class="muted">正在确认管理权限…</span></div>
  <template v-else-if="view === 'unavailable'">
    <PageHeader :title="title" />
    <KbAlert type="warning" show-icon title="当前无法确认管理权限，管理功能暂不可用。">
      <template #action><ElButton :disabled="access.checking" @click="access.check(true)">重新确认</ElButton></template>
    </KbAlert>
  </template>
  <template v-else-if="view === 'spaces'">
    <PageHeader :title="title" />
    <KbAlert type="warning" title="当前无法读取可管理空间。">
      <template #action><ElButton @click="access.check(true)">重新确认</ElButton></template>
    </KbAlert>
  </template>
  <template v-else-if="view === 'denied'">
    <PageHeader :title="title" />
    <KbAlert type="info" show-icon title="当前账号没有此管理页面的读取资格。" />
  </template>
  <slot v-else />
</template>
