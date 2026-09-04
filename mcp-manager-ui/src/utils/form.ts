/**
 * 覆盖式表单的两个通用动作：提交裁剪与多值文本互转。
 *
 * 存在的理由是后端对 null 与空串的语义不同：
 * `null` = 不修改，`''` = 清除覆盖、回落基座值。
 * 如果把回显出来的生效值原样提交回去，就会凭空造出一条「与基座完全相同」的覆盖，
 * overlayStatus 从 NONE 变成 ACTIVE，差异页上多出一堆无意义的改动。
 * 所以只提交用户真正动过的字段。
 */
export function changed<T>(current: T, initial: T): T | undefined {
  return current === initial ? undefined : current
}

/** 多行文本 → 去空行、去首尾空白的数组（上游地址、静态令牌）。 */
export function splitLines(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

export function joinLines(values: string[] | undefined): string {
  return values ? values.join('\n') : ''
}

/** 逗号或换行分隔 → 字符串数组（scope 列表）。 */
export function splitCsv(text: string): string[] {
  return text
    .split(/[,\r\n]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

export function joinCsv(values: (string | number)[] | undefined): string {
  return values ? values.join(', ') : ''
}

/**
 * 逗号分隔 → 数字数组（重试状态码）。
 *
 * 非数字项直接丢弃而不是抛错：这里的输入来自人手，报「格式错误」不如
 * 让它保持空数组（语义 = 任何状态码都不重试），后端也接受空数组。
 */
export function splitCsvNumbers(text: string): number[] {
  return splitCsv(text)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item))
}