<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

type Availability = 'PUBLIC_DOWNLOAD' | 'REQUIRES_STEAM_AUTH' | 'UNSUPPORTED' | 'NOT_FOUND'
type DownloadState = 'QUEUED' | 'RESOLVING' | 'DOWNLOADING' | 'COMPLETED' | 'FAILED'

interface WorkshopItem {
  appId: number
  publishedFileId: string
  title: string
  fileName: string | null
  fileSizeBytes: number | null
  previewUrl: string | null
  description: string | null
  workshopUrl: string
  availability: Availability
  availabilityMessage: string
}

interface DownloadTask {
  id: string
  title: string
  fileName: string
  state: DownloadState
  writtenBytes: number
  totalBytes: number | null
  error: string | null
  completedAt: string | null
  fileUrl: string | null
}

interface AdminStatus {
  configured: boolean
  authenticated: boolean
  steamId?: string
  expiresAt?: string
}

const appId = ref('646570')
const publishedFileId = ref('')
const item = ref<WorkshopItem | null>(null)
const downloads = ref<DownloadTask[]>([])
const adminStatus = ref<AdminStatus>({ configured: false, authenticated: false })
const isResolving = ref(false)
const isStartingDownload = ref(false)
const adminOpen = ref(false)
const message = ref('')
let pollTimer: number | undefined

const canDownload = computed(() => item.value?.availability === 'PUBLIC_DOWNLOAD' && !isStartingDownload.value)
const activeCount = computed(() => downloads.value.filter((task) => ['QUEUED', 'RESOLVING', 'DOWNLOADING'].includes(task.state)).length)
const completeCount = computed(() => downloads.value.filter((task) => task.state === 'COMPLETED').length)

function formatBytes(value: number | null | undefined) {
  if (!value) return '未知大小'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const level = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  return `${(value / 1024 ** level).toFixed(level ? 1 : 0)} ${units[level]}`
}

function formatTime(value: string | null | undefined) {
  if (!value) return '--'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function progress(task: DownloadTask) {
  if (!task.totalBytes || task.state === 'FAILED') return 0
  return Math.min(100, Math.round((task.writtenBytes / task.totalBytes) * 100))
}

function availabilityText(value: Availability) {
  return {
    PUBLIC_DOWNLOAD: '公开可下载',
    REQUIRES_STEAM_AUTH: 'Steam 授权内容',
    UNSUPPORTED: '暂不支持',
    NOT_FOUND: '未找到',
  }[value]
}

function stateText(value: DownloadState) {
  return {
    QUEUED: '等待中',
    RESOLVING: '解析中',
    DOWNLOADING: '下载中',
    COMPLETED: '已完成',
    FAILED: '失败',
  }[value]
}

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.body) headers.set('Content-Type', 'application/json')
  const response = await fetch(url, { ...init, headers, credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: '请求失败，请稍后重试。' })) as { message?: string }
    throw new Error(body.message || '请求失败，请稍后重试。')
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

async function resolveItem() {
  message.value = ''
  item.value = null
  if (!appId.value.trim() || !publishedFileId.value.trim()) {
    message.value = '请输入游戏 AppID 和创意工坊条目 ID。'
    return
  }
  isResolving.value = true
  try {
    item.value = await api<WorkshopItem>(`/api/workshop/item?appId=${encodeURIComponent(appId.value)}&publishedFileId=${encodeURIComponent(publishedFileId.value)}`)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '无法解析创意工坊条目。'
  } finally {
    isResolving.value = false
  }
}

async function startDownload() {
  if (!item.value || !canDownload.value) return
  isStartingDownload.value = true
  message.value = ''
  try {
    await api<DownloadTask>('/api/downloads', {
      method: 'POST',
      body: JSON.stringify({ appId: String(item.value.appId), publishedFileId: item.value.publishedFileId }),
    })
    await refreshDownloads()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '无法创建下载任务。'
  } finally {
    isStartingDownload.value = false
  }
}

async function refreshDownloads() {
  try {
    downloads.value = await api<DownloadTask[]>('/api/downloads')
  } catch {
    // A transient refresh failure does not alter an existing transfer display.
  }
}

async function refreshAdmin() {
  try {
    adminStatus.value = await api<AdminStatus>('/api/admin/status')
  } catch {
    adminStatus.value = { configured: false, authenticated: false }
  }
}

function loadExample() {
  appId.value = '646570'
  publishedFileId.value = '3677098410'
  void resolveItem()
}

function steamLogin() {
  window.location.assign('/api/admin/steam/login')
}

async function logout() {
  await api<void>('/api/admin/logout', { method: 'POST' })
  await refreshAdmin()
}

onMounted(() => {
  void refreshDownloads()
  void refreshAdmin()
  pollTimer = window.setInterval(() => void refreshDownloads(), 1500)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <main class="shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="Workshop Vault 首页">
        <span class="brand-mark"><i></i><i></i><i></i></span>
        <span>WORKSHOP <b>VAULT</b></span>
      </a>
      <div class="topbar-actions">
        <a class="text-link" href="https://steamcommunity.com/workshop/" target="_blank" rel="noreferrer">Steam Workshop</a>
        <button class="admin-trigger" :class="{ active: adminOpen }" type="button" @click="adminOpen = !adminOpen">
          <span class="status-dot" :class="{ online: adminStatus.authenticated }"></span>管理控制台
        </button>
      </div>
    </header>

    <section class="hero">
      <p class="eyebrow">PUBLIC FILE RELAY / 01</p>
      <h1>把创意<br><em>带出工坊。</em></h1>
      <p class="hero-copy">输入 Steam 游戏与工坊条目编号。服务端只下载 Steam 对外公开的直连文件，并完整保留受限内容的授权边界。</p>
      <div class="hero-rail" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
    </section>

    <section class="workspace" :class="{ 'admin-is-open': adminOpen }">
      <section class="resolver-card">
        <div class="section-heading">
          <div><p class="eyebrow">NEW TRANSFER</p><h2>解析条目</h2></div>
          <button class="example-button" type="button" @click="loadExample">加载示例</button>
        </div>
        <form class="resolver-form" @submit.prevent="resolveItem">
          <label><span>游戏 APPID</span><input v-model="appId" inputmode="numeric" autocomplete="off" placeholder="例如 646570" /></label>
          <label><span>WORKSHOP ITEM ID</span><input v-model="publishedFileId" inputmode="numeric" autocomplete="off" placeholder="粘贴创意工坊条目 ID" /></label>
          <button class="primary-button" type="submit" :disabled="isResolving"><span>{{ isResolving ? '读取 Steam 中' : '解析条目' }}</span><span>→</span></button>
        </form>
        <p v-if="message" class="inline-error">{{ message }}</p>

        <article v-if="item" class="item-card">
          <div class="item-preview" :class="{ placeholder: !item.previewUrl }">
            <img v-if="item.previewUrl" :src="item.previewUrl" :alt="item.title" referrerpolicy="no-referrer" />
            <span v-else>WV</span>
          </div>
          <div class="item-details">
            <div class="item-title-line"><p class="eyebrow">APP {{ item.appId }} / #{{ item.publishedFileId }}</p><span class="availability" :class="item.availability.toLowerCase()">{{ availabilityText(item.availability) }}</span></div>
            <h3>{{ item.title }}</h3>
            <p class="item-description">{{ item.description || 'Steam 未提供可显示的描述。' }}</p>
            <div class="item-meta"><span>{{ item.fileName || '未提供文件名' }}</span><span>{{ formatBytes(item.fileSizeBytes) }}</span><a :href="item.workshopUrl" target="_blank" rel="noreferrer">在 Steam 查看 ↗</a></div>
            <div class="item-action-row"><p>{{ item.availabilityMessage }}</p><button v-if="item.availability === 'PUBLIC_DOWNLOAD'" class="download-button" type="button" :disabled="!canDownload" @click="startDownload">{{ isStartingDownload ? '正在创建...' : '开始下载' }}</button></div>
          </div>
        </article>
      </section>

      <aside v-if="adminOpen" class="admin-panel">
        <button class="panel-close" type="button" aria-label="关闭管理控制台" @click="adminOpen = false">×</button>
        <p class="eyebrow">ADMIN / STEAM OPENID</p><h2>控制台</h2><div class="admin-seal"><span>STEAM</span><strong>01</strong></div>
        <template v-if="adminStatus.authenticated">
          <p class="admin-state success">已验证管理员</p>
          <dl><div><dt>SteamID64</dt><dd>{{ adminStatus.steamId }}</dd></div><div><dt>会话到期</dt><dd>{{ formatTime(adminStatus.expiresAt) }}</dd></div></dl>
          <button class="outline-button" type="button" @click="logout">退出登录</button>
        </template>
        <template v-else-if="adminStatus.configured">
          <p class="admin-copy">使用已加入服务器白名单的 Steam 账号验证身份。登录仅使用 Steam OpenID，不会索取密码、令牌或 Cookie。</p>
          <button class="steam-button" type="button" @click="steamLogin"><span>●</span> 使用 Steam 登录</button>
        </template>
        <template v-else><p class="admin-state warning">未配置管理员</p><p class="admin-copy">请在服务端设置 <code>ADMIN_STEAM_IDS</code> 后启用 Steam 管理员登录。</p></template>
      </aside>
    </section>

    <section class="queue-section">
      <div class="queue-heading"><div><p class="eyebrow">TRANSFER LOG</p><h2>下载队列 <sup>{{ downloads.length }}</sup></h2></div><div class="queue-stats"><span><b>{{ activeCount }}</b> 进行中</span><span><b>{{ completeCount }}</b> 已完成</span></div></div>
      <div v-if="downloads.length" class="task-list">
        <article v-for="task in downloads" :key="task.id" class="task-row">
          <div class="task-index">{{ task.id.slice(0, 4).toUpperCase() }}</div>
          <div class="task-main"><div class="task-name-line"><h3>{{ task.title }}</h3><span :class="['task-state', task.state.toLowerCase()]">{{ stateText(task.state) }}</span></div><p>{{ task.fileName }} · {{ formatBytes(task.writtenBytes) }}<template v-if="task.totalBytes"> / {{ formatBytes(task.totalBytes) }}</template></p><div class="progress-track"><span :style="{ width: `${progress(task)}%` }"></span></div><p v-if="task.error" class="task-error">{{ task.error }}</p></div>
          <div class="task-side"><span>{{ task.state === 'COMPLETED' ? formatTime(task.completedAt) : `${progress(task)}%` }}</span><a v-if="task.fileUrl" :href="task.fileUrl">保存文件 ↓</a></div>
        </article>
      </div>
      <div v-else class="queue-empty"><span>00</span><p>尚无下载任务。解析一个公开条目以开始。</p></div>
    </section>
    <footer><span>WORKSHOP VAULT / PUBLIC RELAY</span><span>只处理 Steam 公开 <code>file_url</code> 内容</span></footer>
  </main>
</template>
