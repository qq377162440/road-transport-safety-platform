import request from '@/api/request'
import { ElMessage } from 'element-plus'

/**
 * D 阶段归档 PDF 下载/打印工具。
 * 后端 /api/file/{id}/download 需要 JWT，不能用 <a href> 直链；
 * 统一用 axios blob 携带 token 拉取，再触发浏览器另存。
 *
 * 注意：不要用 window.open(blob:) —— 在托管预览 iframe / 受限上下文中
 * blob 导航会被 Chrome 拒绝（显示"无法打开此文件"），统一改为触发下载。
 */
async function fetchPdf(fileId) {
  const res = await request.get(`/file/${fileId}/download`, { responseType: 'blob' })
  const raw = res instanceof Blob ? res : (res?.data ?? res)
  const blob = raw instanceof Blob ? raw : new Blob([raw], { type: 'application/pdf' })
  // 校验拿到的确实是 PDF：若是后端 JSON 错误被当成 blob，给用户明确报错而不是坏文件
  if (blob.type && !blob.type.includes('pdf') && blob.type.includes('json')) {
    throw new Error('服务端返回错误，PDF 获取失败')
  }
  if (blob.size < 100 || (await blob.slice(0, 5).text()) !== '%PDF-') {
    throw new Error('PDF 内容异常，请重试或联系管理员')
  }
  return blob
}

/** 保存 PDF 到本地（触发浏览器下载） */
function saveBlob(blob, filename = '归档资料.pdf') {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

export async function openPdf(fileId, filename) {
  try {
    const blob = await fetchPdf(fileId)
    // 受限上下文无法可靠地用 blob 打开新标签，统一走下载 + 提示打印
    saveBlob(blob, filename || '归档资料.pdf')
    ElMessage.success('PDF 已开始下载，打开后可直接打印')
  } catch (e) {
    ElMessage.error(e.message || 'PDF 打开失败')
  }
}

export async function downloadPdf(fileId, filename = '归档资料.pdf') {
  try {
    const blob = await fetchPdf(fileId)
    saveBlob(blob, filename)
  } catch (e) {
    ElMessage.error(e.message || 'PDF 下载失败')
  }
}
