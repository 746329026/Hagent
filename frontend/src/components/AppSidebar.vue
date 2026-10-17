<script setup>
import { nextTick, ref } from 'vue'

/**
 * ★ logo 用 {@code /logo.png}（public 目录）而不是 {@code import logo from '../assets/logo.png'}：
 * 同一个 527KB 的图片不该在仓库里存两份（一份给 favicon、一份给组件）。
 * public 下的文件会被原样拷到产物根目录，`/logo.png` 在开发与构建后都可用。
 */

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  currentId: { type: String, default: null },
  role: { type: String, default: 'employee' },
  roles: { type: Array, default: () => [] },
})

const emit = defineEmits(['new', 'select', 'rename', 'update:role'])

const renamingId = ref(null)
const draft = ref('')
const roleMenuOpen = ref(false)
const renameInputEl = ref(null)

/**
 * ★ 这里必须用函数 ref，不能写 {@code ref="renameInput"}：
 * 该 input 位于 {@code v-for} 内部，Vue 3 对 v-for 里的模板 ref 会收集成<b>数组</b>，
 * 于是 {@code renameInput.value?.focus()} 永远拿不到元素（focus 静默失败）。
 */
function setRenameRef(el) {
  if (el) {
    renameInputEl.value = el
  }
}

function startRename(session) {
  renamingId.value = session.conversationId
  draft.value = session.title || ''
  nextTick(() => renameInputEl.value?.focus())
}

function commitRename() {
  if (!renamingId.value) {
    return
  }
  const id = renamingId.value
  const title = draft.value.trim()
  renamingId.value = null
  if (title && title !== (props.sessions.find((s) => s.conversationId === id)?.title || '')) {
    emit('rename', id, title)
  }
}

function cancelRename() {
  renamingId.value = null
}

function pickRole(key) {
  roleMenuOpen.value = false
  emit('update:role', key)
}

const roleLabel = (key) => props.roles.find((r) => r.key === key)?.label ?? key
const roleHint = (key) => props.roles.find((r) => r.key === key)?.hint ?? ''
</script>

<template>
  <aside class="sidebar" style="position: relative">
    <div class="sidebar-brand">
      <img src="/logo.png" alt="Hagent" />
      <span class="sidebar-brand-name">Hagent</span>
    </div>

    <div class="sidebar-actions">
      <button class="btn-new" type="button" @click="emit('new')">＋ 新对话</button>
    </div>

    <div class="session-list">
      <div class="session-group-title">最近</div>

      <div
        v-for="session in sessions"
        :key="session.conversationId"
        class="session-item"
        :class="{ active: session.conversationId === currentId }"
        @click="emit('select', session.conversationId)"
      >
        <template v-if="renamingId === session.conversationId">
          <input
            :ref="setRenameRef"
            v-model="draft"
            class="session-rename-input"
            maxlength="128"
            @click.stop
            @keydown.enter.prevent="commitRename"
            @keydown.esc.prevent="cancelRename"
            @blur="commitRename"
          />
        </template>
        <template v-else>
          <span class="session-title" :title="session.title || '新会话'">
            {{ session.title || '新会话' }}
          </span>
          <span class="session-meta">
            <span class="tag" :class="session.mode === 'workflow' ? 'tag-workflow' : 'tag-chat'">
              {{ session.mode === 'workflow' ? '工作流' : '对话' }}
            </span>
            <button class="icon-btn" type="button" title="重命名" @click.stop="startRename(session)">
              ✎
            </button>
          </span>
        </template>
      </div>

      <div v-if="!sessions.length" class="session-group-title" style="padding-top: 18px">
        还没有会话
      </div>
    </div>

    <div class="sidebar-footer">
      <button class="role-button" type="button" @click="roleMenuOpen = !roleMenuOpen">
        <span class="role-avatar">{{ roleLabel(role).slice(0, 1) }}</span>
        <span class="role-text">
          <span class="role-name">{{ roleLabel(role) }}</span>
          <span class="role-hint">{{ roleHint(role) }}</span>
        </span>
        <span style="color: var(--text-faint)">⌃</span>
      </button>
    </div>

    <div v-if="roleMenuOpen" class="role-menu">
      <button
        v-for="item in roles"
        :key="item.key"
        class="role-menu-item"
        :class="{ active: item.key === role }"
        type="button"
        @click="pickRole(item.key)"
      >
        {{ item.label }}
        <small>{{ item.hint }}</small>
      </button>
      <div style="padding: 6px 10px 2px; font-size: 11px; color: var(--text-faint)">
        切换身份会改变检索可见的密级 —— 用来直观验证权限过滤
      </div>
    </div>
  </aside>
</template>
