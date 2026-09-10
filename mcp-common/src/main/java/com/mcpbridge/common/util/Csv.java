package com.mcpbridge.common.util;

/**
 * CSV 输出工具（RFC 4180）。
 *
 * <p>把三个最容易踩的坑集中处理，避免每个导出端点各写一套：
 * <ol>
 *   <li><b>公式注入</b>：Excel / WPS 会把以 {@code = + - @} 或制表符、回车开头的单元格当公式执行
 *       （{@code =HYPERLINK(...)} 这类老问题）。导出内容里 actorName、targetId、detail 都可能是
 *       外部可控字符串，因此统一加 {@code '} 前缀——Excel 会把它当"强制文本"标记，不显示出来。</li>
 *   <li><b>UTF-8 BOM</b>：不加 BOM，Excel 在中文 Windows 上会按 GBK 解码，中文列全乱码。
 *       代价是某些命令行工具会在首格看到 {@code \uFEFF}，这个取舍以"业务同学能直接双击打开"为准。</li>
 *   <li><b>行尾</b>：RFC 4180 要求 CRLF；Excel 对 LF 也能忍，但 CRLF 更保险。</li>
 * </ol>
 *
 * <p>本类只做文本编码，不关心列的含义——列定义属于各导出端点的业务。
 */
public final class Csv {

    /** UTF-8 BOM。放在文件最开头。 */
    public static final String BOM = "\uFEFF";

    public static final String CRLF = "\r\n";

    /** 会被表格软件解释为公式的起始字符。制表符与回车是历史遗留的旁路。 */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    private Csv() {
    }

    /** 拼一行（含行尾 CRLF）。 */
    public static String row(Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells[i]));
        }
        return line.append(CRLF).toString();
    }

    /**
     * 编码单个单元格：必要时加引号、内部引号翻倍，公式起始字符前加 {@code '}。
     *
     * @param value 任意值；{@code null} 输出为空串（而不是字符串 "null"——那会让"没有值"变成一个有值的文本）
     */
    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "";
        }
        if (FORMULA_STARTERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (!needsQuoting(text)) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static boolean needsQuoting(String text) {
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        // 首尾空格在多数解析器里会被吃掉，必须靠引号保住
        if (first == ' ' || last == ' ') {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r' || c == '\t') {
                return true;
            }
        }
        return false;
    }
}
