package com.mcpbridge.manager.web;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.domain.DocSource;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.service.RegistrationService;
import com.mcpbridge.manager.web.dto.ApiResponse;
import com.mcpbridge.manager.web.dto.PageView;
import com.mcpbridge.manager.web.dto.RegistrationDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST 接口注册与文档解析（REG-01 / REG-02 / REG-03，BR-1）。
 *
 * <p>三种入口：URL 拉取、文件上传、直接贴文本。三者最终都走同一个 {@code create}，
 * 因此「原始文档只读 + sha256 双保险」「1 注册 = 1 Server」这些约束只实现一次。
 */
@RestController
@RequestMapping("/api/v1/registrations")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('registration:read')")
    public ApiResponse<PageView<RegistrationDtos.View>> page(@PageableDefault(size = 20) Pageable pageable,
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(registrationService.page(pageable, principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('registration:read')")
    public ApiResponse<RegistrationDtos.View> view(@PathVariable Long id,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(registrationService.view(id, principal));
    }

    /** 按 URL 注册。抓取在事务外进行，避免慢站点长期占用数据库连接。 */
    @PostMapping("/by-url")
    @PreAuthorize("hasAuthority('registration:create')")
    public ApiResponse<RegistrationDtos.View> createByUrl(
            @Valid @RequestBody RegistrationDtos.CreateByUrlRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(registrationService.createByUrl(request, principal));
    }

    /** 文件上传注册（multipart）。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('registration:create')")
    public ApiResponse<RegistrationDtos.View> upload(@RequestParam("name") String name,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "deptId", required = false) Long deptId,
                                                     @RequestParam(value = "pathSegment", required = false)
                                                     String pathSegment,
                                                     @RequestParam(value = "targetServerId", required = false)
                                                     Long targetServerId,
                                                     @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(registrationService.createByUpload(name, file, deptId, pathSegment, targetServerId, principal));
    }

    /** 直接粘贴 OpenAPI/Swagger 文本注册。Content-Type 用 text/plain，避免与 JSON 请求体混淆。 */
    @PostMapping(value = "/by-text", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE, "text/yaml", "application/yaml"})
    @PreAuthorize("hasAuthority('registration:create')")
    public ApiResponse<RegistrationDtos.View> createByText(@RequestParam("name") String name,
                                                           @RequestParam(value = "deptId", required = false) Long deptId,
                                                           @RequestParam(value = "pathSegment", required = false)
                                                           String pathSegment,
                                                           @RequestParam(value = "targetServerId", required = false)
                                                           Long targetServerId,
                                                           @RequestBody String rawDoc,
                                                           @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(registrationService.create(name, rawDoc, DocSource.FILE, "inline",
                deptId, pathSegment, targetServerId, principal));
    }

    /**
     * 读取原始文档（BR-2：原始 Swagger 只读）。返回前会重算 sha256 与登记值比对，
     * 不一致说明库里的文档被绕过应用直接改过，直接拒绝并要求运维介入。
     */
    @GetMapping(value = "/{id}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasAuthority('registration:read')")
    public String rawDoc(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        return registrationService.rawDoc(id, principal);
    }

    /**
     * 重新解析（REG-03）。不带请求体时按登记的 sourceRef 重新抓取（仅 URL 来源支持）。
     * 返回差异报告：新增 / 删除 / 变更 / 挂起的覆盖 / 保留的覆盖。
     */
    @PostMapping(value = "/{id}/reimport", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE, "text/yaml", "application/yaml"})
    @PreAuthorize("hasAuthority('registration:reimport')")
    public ApiResponse<RegistrationDtos.DiffReport> reimport(@PathVariable Long id,
                                                             @RequestBody(required = false) String rawDoc,
                                                             @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(registrationService.reimport(id, rawDoc, principal));
    }

    @PostMapping(value = "/{id}/reimport-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('registration:reimport')")
    public ApiResponse<RegistrationDtos.DiffReport> reimportUpload(@PathVariable Long id,
                                                                   @RequestParam("file") MultipartFile file,
                                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw PlatformException.validation("请上传接口文档文件", Map.of("field", "file"));
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PlatformException(ErrorCode.PARSE_FAILED, "读取上传文件失败", e);
        }
        return ApiResponse.ok(registrationService.reimport(id, text, principal));
    }
}