<script setup>
/**
 * 输入框 + 发送/停止。
 *
 * <p>两个刻意的行为：
 * <ul>
 *   <li><b>Enter 发送、Shift+Enter 换行</b>：对话场景里 Enter 换行会让"发出去"变成一次点击；</li>
 *   <li><b>模式选择器在已有会话时被禁用</b>：模式在会话<b>第一次创建</b>时确定，
 *       之后由后端记住（{@code chat_conversation.mode}）。
 *       允许在这里改，前端就会显示一个<b>后端不会采纳</b>的值 —— 那就是 UI 在撒谎。</li>
 * </ul>
 */
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  streaming: { type: Boolean, default: false },
  modeLocked: { type: Boolean, default: false },
  mode: { type: String, default: 'chat' },
})

const emit = defineEmits(['send', 'stop', 'update:mode'])

const text = ref('')
const textarea = ref(null)

function autoGrow() {
  const el = textarea.value
  if (!el) {
    return
  }
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

watch(text, () => nextTick(autoGrow))

function submit() {
  const value = text.value.trim()
  if (!value || props.streaming) {
    return
  }
  emit('send', value)
  text.value = ''
  nextTick(autoGrow)
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

/** 由父组件调用：发送外部提供的提示语（空态里的示例问题）。 */
function fill(value) {
  text.value = value
  nextTick(() => {
    autoGrow()
    textarea.value?.focus()
  })
}

defineExpose({ fill })
</script>

<template>
  <div class="composer-wrap">
    <div class="composer">
      <textarea
        ref="textarea"
        v-model="text"
        rows="1"
        placeholder="问点什么…（Enter 发送，Shift + Enter 换行）"
        @keydown="onKeydown"
      />
      <div class="composer-bar">
        <select
          class="mode-select"
          :value="mode"
          :disabled="modeLocked || streaming"
          :title="modeLocked ? '会话创建后模式不可更改（决定历史预算）' : '决定本次会话的历史预算'"
          @change="emit('update:mode', $event.target.value)"
        >
          <option value="chat">对话</option>
          <option value="workflow">工作流</option>
        </select>
        <span class="composer-hint">
          {{ modeLocked ? '当前会话模式已固定' : '模式在第一条消息时确定' }}
        </span>
        <button v-if="streaming" class="btn-send stop" type="button" @click="emit('stop')">
          停止
        </button>
        <button v-else class="btn-send" type="button" :disabled="!text.trim()" @click="submit">
          发送
        </button>
      </div>
    </div>
  </div>
</template>
