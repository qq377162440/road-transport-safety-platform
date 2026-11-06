import request from '@/api/request'
import { ElMessage } from 'element-plus'

/**
 * D 阶段归档 PDF 下载/打印工具。
 * 后端 /api/file/{id}/download 需要 JWT，不能用 <a href> 直链；
 * 统一用 axios blob 携带 token 拉取，再生成临时 URL 供浏览器打开（可直接打印）或另存。
 */
async function fetchPdf(fileId) {
  const res = await request.get(`/file/${fileId}/download`, { responseType: 'blob' })
  const blob = res instanceof Blob ? res : new Blob([res?.data ?? res], { type: 'application/pdf' })
  return URL.createObjectURL(blob)
}

export async function openPdf(fileId) {
  try {
    const url = await fetchPdf(fileId)
    window.open(url, '_blank')
  } catch (e) {
    ElMessage.error(e.message || 'PDF 打开失败')
  }
}

export async function downloadPdf(fileId, filename = '归档资料.pdf') {
  try {
    const url = await fetchPdf(fileId)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (e) {
    ElMessage.error(e.message || 'PDF 下载失败')
  }
}
