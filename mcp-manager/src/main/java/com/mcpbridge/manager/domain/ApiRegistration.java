package com.mcpbridge.manager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一次注册 = 一份 Swagger/OpenAPI 文档 = 一个 REST 服务（BR-1）。
 *
 * <p>{@code rawDoc} 为原文只读存档，配合 {@code rawDocSha256} 做双保险校验（BR-2 / N3）：
 * 平台任何写入路径都不得修改它。
 */
@Entity
@Table(name = "api_registration")
public class ApiRegistration extends BaseEntity {

    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_source", nullable = false, length = 16)
    private DocSource docSource;

    /** URL 来源时的原始地址；文件来源时存文件名。 */
    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    /** 检测到的规范版本："2.0" 或 "3.0.x" / "3.1.x"。 */
    @Column(name = "swagger_version", length = 16)
    private String swaggerVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_doc", columnDefinition = "jsonb")
    private String rawDoc;

    @Column(name = "raw_doc_sha256", length = 64)
    private String rawDocSha256;

    /** 文档导入次数：首次为 1，每次 re-import 递增（REG-03）。 */
    @Column(name = "doc_version", nullable = false)
    private int docVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RegistrationStatus status = RegistrationStatus.PARSING;

    /** 结构化诊断：错误位置 / 缺失字段（REG-01 要求「不白屏」）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String diagnostics;

    @Column(name = "operation_count")
    private int operationCount;

    @Column(name = "created_by")
    private Long createdBy;

    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DocSource getDocSource() { return docSource; }
    public void setDocSource(DocSource docSource) { this.docSource = docSource; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getSwaggerVersion() { return swaggerVersion; }
    public void setSwaggerVersion(String swaggerVersion) { this.swaggerVersion = swaggerVersion; }
    public String getRawDoc() { return rawDoc; }
    public void setRawDoc(String rawDoc) { this.rawDoc = rawDoc; }
    public String getRawDocSha256() { return rawDocSha256; }
    public void setRawDocSha256(String rawDocSha256) { this.rawDocSha256 = rawDocSha256; }
    public int getDocVersion() { return docVersion; }
    public void setDocVersion(int docVersion) { this.docVersion = docVersion; }
    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus status) { this.status = status; }
    public String getDiagnostics() { return diagnostics; }
    public void setDiagnostics(String diagnostics) { this.diagnostics = diagnostics; }
    public int getOperationCount() { return operationCount; }
    public void setOperationCount(int operationCount) { this.operationCount = operationCount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}