package com.mcpbridge.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 编码规则（MGM-05 审计导出）。
 *
 * <p>这些断言都是"错一个字符，整份文件在 Excel 里就错位或被当公式执行"的地方，
 * 因此逐条钉死而不是只测一个正常值。
 */
class CsvTest {

    @Test
    @DisplayName("普通值不加引号，null 输出空串而不是字符串 null")
    void encodesPlainCells() {
        assertThat(Csv.row(1L, "server", "publish.execute")).isEqualTo("1,server,publish.execute\r\n");
        assertThat(Csv.row((Object) null)).isEqualTo("\r\n");
        assertThat(Csv.row(1L, null, "x")).isEqualTo("1,,x\r\n");
    }

    @Test
    @DisplayName("含逗号、引号、换行的值加引号，内部引号翻倍")
    void quotesAmbiguousCells() {
        assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(Csv.cell("tab\there")).isEqualTo("\"tab\there\"");
    }

    @Test
    @DisplayName("jsonb detail 原样入列：它本身含逗号与双引号，必须整体成一个单元格")
    void keepsJsonDetailInOneCell() {
        String detail = "{\"from\":\"SHARED\",\"to\":\"PRIVATE\",\"note\":\"a,b\"}";

        String line = Csv.row("cluster.update", detail);

        assertThat(line).isEqualTo("cluster.update,\"" + detail.replace("\"", "\"\"") + "\"\r\n");
        // 行内除了包裹用的两个引号，没有裸露的逗号分隔出第三个单元格
        assertThat(line.split("\r\n")[0]).startsWith("cluster.update,\"");
    }

    @Test
    @DisplayName("公式注入：= + - @ 与制表符/回车开头的值前面加单引号")
    void neutralisesFormulaInjection() {
        assertThat(Csv.cell("=HYPERLINK(\"http://evil\",\"x\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
        assertThat(Csv.cell("+1+1")).isEqualTo("'+1+1");
        assertThat(Csv.cell("-2+3")).isEqualTo("'-2+3");
        assertThat(Csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        // 加前缀后不再以公式字符开头，因此普通值不需要引号
        assertThat(Csv.cell("plain")).isEqualTo("plain");
    }

    @Test
    @DisplayName("首尾空格必须靠引号保住，否则解析端会吃掉")
    void keepsSurroundingSpaces() {
        assertThat(Csv.cell(" trimmed ")).isEqualTo("\" trimmed \"");
    }

    @Test
    @DisplayName("BOM 与 CRLF 常量被导出端点直接使用")
    void exposesBomAndCrlf() {
        assertThat(Csv.BOM).isEqualTo("\uFEFF");
        assertThat(Csv.CRLF).isEqualTo("\r\n");
    }
}
