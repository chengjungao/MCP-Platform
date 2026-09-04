/**
 * 复制到剪贴板。
 *
 * `navigator.clipboard` 只在安全上下文（https 或 localhost）存在，而控制台很可能通过
 * http + 内网 IP 访问，那时它是 undefined。所以保留 textarea + execCommand 兜底，
 * 并把成败如实返回，由调用方决定提示什么——静默失败会让用户以为端点已经在剪贴板里。
 */
export async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒或实现差异，继续走兜底
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  // 移出视口而不是 display:none：后者不参与选区，select() 拿不到内容
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(area)
  return ok
}