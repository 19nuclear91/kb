<script setup lang="ts">
import { ref } from 'vue';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import Feedback from '../ui/Feedback.vue';
import { notificationPage } from './api';
import { useUnreadCount } from './unread';
import NotificationList from './NotificationList.vue';

const auth = useSession(), revision = ref(0);
const query = useResource(signal => notificationPage(auth.session, null, signal), () => `notifications/${revision.value}`);
const unread = useUnreadCount();
function reload() {
  revision.value++;
  unread.refresh();
}
</script>

<template>
  <Feedback :loading="query.loading" :error="query.error" :retry="reload" />
  <NotificationList v-if="query.data" :key="revision" :first="query.data" :reload="reload" :on-read="unread.refresh" />
</template>
