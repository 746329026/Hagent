<script setup>
/**
 * 执行轨迹 —— 悬浮在对话区右上角的窄框（DSH 风格）。
 *
 * <h2>★ 它显示什么，以及<b>不</b>显示什么</h2>
 *
 * <p>一个 "step" = <b>一次模型调用</b>（后端 {@code StepStarted} 事件）。
 * 一步里模型可能请求 0..N 个工具，工具挂在它下面。
 *
 * <p>⚠️ <b>它不叫"任务拆解"</b>：任务拆解是工作流模式（阶段 12）才有的东西。
 * 今天这里的一行就是"模型被调用了一次"，把它叫成任务拆解等于让 UI 承诺一个
 * 不存在的功能。
 *
 * <p>⚠️ <b>工具状态只到"已返回"，不画成功/失败</b>：后端的 {@code ToolResult}
 * 刻意没有 error 字段（框架把工具异常翻成了文本，{@code ToolResponseMessage}
 * 也没有错误标志）。所以"它失败了"这件事<b>前端无法知道</b> ——
 * 画个绿勾就是编造。
 */
import { computed, ref } from 'vue'

const props = defineProps({
  steps: { type: Array, default: () => [] },
  streaming: { type: Boolean, default: false },
})

const collapsed = ref(false)

const stepCount = computed(() => props.steps.length)

const toolCount = computed(() =>
  props.steps.reduce((sum, step) => sum + (step.tools ? step.tools.length : 0), 0)
)

function stateLabel(step) {
  if (step.tools && step.tools.some((tool) => tool.state === 'running')) {
    return '调用中'
  }
  if (step.state === 'answering') {
    return '作答中'
  }
  if (step.state === 'done') {
    return '完成'
  }
  return '思考中'
}
</script>

<template>
  <div v-if="steps.length" class="task-panel">
    <div class="task-panel-head" @click="collapsed = !collapsed">
      <span class="task-panel-title">执行轨迹</span>
      <span style="color: var(--text-faint)">
        {{ stepCount }} 步<template v-if="toolCount"> · {{ toolCount }} 次工具</template>
      </span>
      <span style="color: var(--text-faint)">{{ collapsed ? '展开' : '收起' }}</span>
    </div>

    <div v-if="!collapsed" class="task-panel-body">
      <div
        v-for="step in steps"
        :key="step.step"
        class="task-step"
        :class="{ active: streaming && step.state !== 'done' }"
      >
        <div class="task-step-head">
          <span class="task-step-name">第 {{ step.step }} 步</span>
          <span class="spacer" />
          <span v-if="streaming && step.state !== 'done'" class="spin" />
          <span>{{ stateLabel(step) }}</span>
        </div>

        <div v-for="tool in step.tools" :key="tool.id" class="task-tool">
          <span class="task-tool-name">{{ tool.name }}</span>
          <span class="task-tool-state" :class="tool.state">
            {{ tool.state === 'running' ? '调用中' : '已返回' }}
          </span>
          <div v-if="tool.resultPreview" class="task-tool-result">{{ tool.resultPreview }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
