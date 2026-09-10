package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.util.Csv;
import com.mcpbridge.manager.domain.AuditLog;
import com.mcpbridge.manager.repository.AuditLogRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.security.CurrentPrincipal;
import com.mcpbridge.manager.web.dto.AuditDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计导出（MGM-05）。
 *
 * <p>导出比列表危险得多：它会把"谁在什么时候做了什么"一次性变成文件带走，而且**文件不走信封、
 * 出错时无人盯着看**。所以这里重点钉三件事：
 * <ol>
 *   <li>超上限时<b>抛错而不是截断</b>——被截断的审计文件看起来是完整的；</li>
 *   <li>导出与列表走<b>同一个查询方法</b>，部门隔离不可能被绕过；</li>
 *   <li>含逗号 / 引号 / 换行的 detail 必须整体落在一个单元格里，否则整份文件在 Excel 里错位。</li>
 * </ol>
 */
class AuditExportTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final DepartmentScope scope = mock(DepartmentScope.class);
    private final AuditService service = new AuditService(repository, new CurrentPrincipal());

    private static final AuthPrincipal PRINCIPAL =
            new AuthPrincipal(7L, "admin", "管理员", 1L, Set.of("PLATFORM_ADMIN"), Set.of());

    @Test
    @DisplayName("全量导出：带 BOM 与表头，行数与记录数一致")
    void exportsHeaderAndRows() {
        when(scope.visibleDeptIds(PRINCIPAL)).thenReturn(null);
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(page(List.of(entry(1L), entry(2L)), 2));

        AuditDtos.Export exported = service.exportCsv(null, PRINCIPAL, scope, 50000);

        assertThat(exported.rows()).isEqualTo(2);
        assertThat(exported.csv()).startsWith(Csv.BOM);
        assertThat(exported.csv()).contains("id,createdAt,actorId,actorName,action,targetType,targetId,"
                + "deptId,clientIp,traceId,detail");
        // 表头 1 行 + 数据 2 行
        assertThat(exported.csv().split("\r\n", -1)).hasSize(4);
    }

    @Test
    @DisplayName("超过行数上限直接抛 409，不做静默截断")
    void refusesToTruncate() {
        when(scope.visibleDeptIds(PRINCIPAL)).thenReturn(null);
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(page(List.of(entry(1L)), 50_001));

        assertThatThrownBy(() -> service.exportCsv(null, PRINCIPAL, scope, 50000))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("导出条数超过上限")
                .satisfies(thrown -> assertThat(((PlatformException) thrown).details())
                        .containsEntry("total", 50_001L)
                        .containsEntry("max", 50_000));
    }

    @Test
    @DisplayName("导出与列表共用同一段部门隔离：非平台管理员走 findByDeptIdIn，且 action 过滤同时生效")
    void reusesDepartmentIsolation() {
        when(scope.visibleDeptIds(PRINCIPAL)).thenReturn(Set.of(1L, 2L));
        when(repository.findByActionAndDeptIdInOrderByIdDesc(eq("publish.execute"), any(), any(Pageable.class)))
                .thenReturn(page(List.of(entry(3L)), 1));

        AuditDtos.Export exported = service.exportCsv("  publish.execute  ", PRINCIPAL, scope, 50000);

        assertThat(exported.rows()).isEqualTo(1);
        verify(repository).findByActionAndDeptIdInOrderByIdDesc(
                eq("publish.execute"),
                argThat(deptIds -> deptIds.size() == 2 && deptIds.containsAll(Set.of(1L, 2L))),
                any(Pageable.class));
    }

    @Test
    @DisplayName("多页记录被完整导出，不是只导第一页")
    void walksEveryPage() {
        when(scope.visibleDeptIds(PRINCIPAL)).thenReturn(null);
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                // 总数 1200 > 单页 500：必须翻到第 3 页才结束
                .thenReturn(page(List.of(entry(1L)), 1200, 0))
                .thenReturn(page(List.of(entry(2L)), 1200, 1))
                .thenReturn(page(List.of(entry(3L)), 1200, 2));

        AuditDtos.Export exported = service.exportCsv(null, PRINCIPAL, scope, 50000);

        assertThat(exported.rows()).isEqualTo(3);
    }

    @Test
    @DisplayName("detail 里的逗号与引号只影响一个单元格，不撑破列结构")
    void escapesDetailAsSingleCell() {
        when(scope.visibleDeptIds(PRINCIPAL)).thenReturn(null);
        AuditLog noisy = entry(9L);
        noisy.setDetail("{\"note\":\"a,b\"}");
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(page(List.of(noisy), 1));

        String csv = service.exportCsv(null, PRINCIPAL, scope, 50000).csv();

        assertThat(csv).contains("\"{\"\"note\"\":\"\"a,b\"\"}\"");
        // 内嵌的逗号没有撑出新行：BOM+表头 1 行 + 数据 1 行 + split 出的尾空串
        assertThat(csv.split("\r\n", -1)).hasSize(3);
    }

    @Test
    @DisplayName("null 字段输出空单元格，而不是字符串 null；列数始终与表头一致")
    void writesEmptyCellsForNulls() {
        when(scope.visibleDeptIds(PRINCIPAL)).thenReturn(null);
        AuditLog sparse = new AuditLog();
        sparse.setId(1L);
        sparse.setAction("auth.login");
        sparse.setCreatedAt(Instant.parse("2026-09-10T07:30:00Z"));
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(page(List.of(sparse), 1));

        String csv = service.exportCsv(null, PRINCIPAL, scope, 50000).csv();

        String[] lines = csv.split("\r\n", -1);
        // 列映射正确：id / createdAt 有值，action 在第 5 列，其余全空
        assertThat(csv).contains(
                Csv.row(1L, "2026-09-10T07:30:00Z", null, null, "auth.login", null, null, null, null, null, null));
        // 数据行的列数与表头一致（本 fixture 无内嵌逗号，可以按分隔符数）
        assertThat(separators(lines[1])).isEqualTo(separators(lines[0]));
        assertThat(csv).doesNotContain("null");
    }

    private static int separators(String line) {
        return (int) line.chars().filter(c -> c == ',').count();
    }

    // ------------------------------------------------------------ 构造

    private static AuditLog entry(long id) {
        AuditLog log = new AuditLog();
        log.setId(id);
        log.setActorId(7L);
        log.setActorName("admin");
        log.setAction("publish.execute");
        log.setTargetType("server");
        log.setTargetId("order-api");
        log.setDeptId(1L);
        log.setClientIp("10.0.0.1");
        log.setTraceId("00-trace-span-01");
        log.setDetail("{\"version\":3}");
        log.setCreatedAt(Instant.parse("2026-09-10T07:30:00Z"));
        return log;
    }

    private static Page<AuditLog> page(List<AuditLog> content, long total) {
        return page(content, total, 0);
    }

    private static Page<AuditLog> page(List<AuditLog> content, long total, int pageNumber) {
        // size 用与 AuditService.EXPORT_PAGE_SIZE 一致的 500，让 hasNext 的判定与真实分页同构
        return new PageImpl<>(content, Pageable.ofSize(500).withPage(pageNumber), total);
    }
}
