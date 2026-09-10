package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.common.snapshot.PromptSnapshot;
import com.mcpbridge.common.snapshot.ResourceSnapshot;
import com.mcpbridge.common.util.Json;
import com.mcpbridge.common.util.PromptTemplate;
import com.mcpbridge.manager.domain.AuditAction;
import com.mcpbridge.manager.domain.McpPrompt;
import com.mcpbridge.manager.domain.McpResource;
import com.mcpbridge.manager.domain.McpServer;
import com.mcpbridge.manager.domain.McpTool;
import com.mcpbridge.manager.repository.McpPromptRepository;
import com.mcpbridge.manager.repository.McpResourceRepository;
import com.mcpbridge.manager.repository.McpToolRepository;
import com.mcpbridge.manager.security.AuthPrincipal;
import com.mcpbridge.manager.web.dto.ServerDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resource / Prompt 手动目录（SVR-05 / SVR-06）。
 *
 * <p><b>为什么需要这个服务</b>：Executor 侧的 {@code resources/list|read}、{@code prompts/list|get}
 * 早已实现完整（静态内容、tool 映射、模板渲染、必填参数校验、capabilities 门控），
 * 但控制面一直缺模型与组装，导致快照里这两个数组恒为空——「读端 100%、写端 0%」。
 * 本服务补的是写端。
 *
 * <p>为什么不能从 Swagger 推导：OpenAPI 没有 resource / prompt 的对应概念。这决定了它只能是
 * 手动配置，而不是注册解析的延伸。
 *
 * <p>权限沿用 {@code server:read} / {@code tool:write}，不新增权限点：
 * 这两类对象是 Server「对外能力」的一部分，与 tool 同级；新增权限点会让所有内置角色都要重配一遍。
 */
@Service
public class ResourcePromptService {

    /**
     * Prompt 名规则。比 tool 名宽松一点（允许点和连字符），因为 prompt 名不参与路由，
     * 只作为 {@code prompts/get} 的查询键，且常被写成 {@code order.review} 这样的层次名。
     */
    public static final Pattern PROMPT_NAME = Pattern.compile("^[a-zA-Z0-9_.\\-]{1,128}$");

    /** 参数名必须能被写进模板占位符，否则「声明了一个永远引用不到的参数」。 */
    private static final Pattern ARGUMENT_NAME = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");

    /** Resource URI 规则：必须有 scheme，避免把「订单schema」这种自然语言当成 URI 存进去。 */
    public static final Pattern RESOURCE_URI = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:[^\\s]*$");

    private final ServerAccessGuard accessGuard;
    private final McpResourceRepository resourceRepository;
    private final McpPromptRepository promptRepository;
    private final McpToolRepository toolRepository;
    private final OverlayService overlayService;
    private final AuditService auditService;

    public ResourcePromptService(ServerAccessGuard accessGuard,
                                 McpResourceRepository resourceRepository,
                                 McpPromptRepository promptRepository,
                                 McpToolRepository toolRepository,
                                 OverlayService overlayService,
                                 AuditService auditService) {
        this.accessGuard = accessGuard;
        this.resourceRepository = resourceRepository;
        this.promptRepository = promptRepository;
        this.toolRepository = toolRepository;
        this.overlayService = overlayService;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- Resource

    @Transactional(readOnly = true)
    public List<ServerDtos.ResourceView> resources(Long serverId, AuthPrincipal principal) {
        accessGuard.requireRead(serverId, principal);
        return resourcesOf(serverId);
    }

    /** 列表组装（不做权限判断），供控制器与快照组装共用。 */
    @Transactional(readOnly = true)
    public List<ServerDtos.ResourceView> resourcesOf(Long serverId) {
        Map<Long, String> toolNames = effectiveToolNames(serverId);
        return resourceRepository.findByServerIdOrderBySortOrderAscIdAsc(serverId).stream()
                .map(resource -> toView(resource, toolNames))
                .toList();
    }

    /**
     * 新增或更新一个 Resource。
     *
     * @param resourceId 为空表示新增；非空表示更新（URI 允许改，改完按新 URI 做唯一性校验）
     */
    @Transactional
    public ServerDtos.ResourceView saveResource(Long serverId, Long resourceId,
                                                ServerDtos.ResourceRequest request,
                                                AuthPrincipal principal) {
        McpServer server = accessGuard.requireManage(serverId, principal);
        String uri = requireUri(request.uri());
        boolean staticContent = request.content() != null && !request.content().isBlank();
        boolean mapped = request.toolId() != null;

        // content 与 toolId 二选一：两个都不给等于声明了一个读不出内容的资源
        if (staticContent && mapped) {
            throw PlatformException.validation("静态内容与映射 tool 只能二选一",
                    Map.of("field", "content",
                            "hint", "要么填 content（静态内容），要么填 toolId（读时调用该 tool）"));
        }
        if (!staticContent && !mapped) {
            throw PlatformException.validation("必须提供静态内容或映射一个 tool",
                    Map.of("fields", List.of("content", "toolId"),
                            "hint", "没有数据来源的 resource 在 resources/read 时必然失败"));
        }
        McpTool tool = mapped ? requireToolOfServer(serverId, request.toolId()) : null;

        McpResource resource = resourceId == null
                ? new McpResource()
                : resourceRepository.findById(resourceId)
                        .filter(existing -> serverId.equals(existing.getServerId()))
                        .orElseThrow(() -> PlatformException.notFound("MCP Resource", resourceId));
        if (resourceId == null) {
            resource.setServerId(serverId);
            resource.setSortOrder(nextResourceOrder(serverId));
        }
        resourceRepository.findByServerIdAndUri(serverId, uri)
                .filter(existing -> !existing.getId().equals(resource.getId()))
                .ifPresent(existing -> {
                    throw PlatformException.validation("该 URI 已存在",
                            Map.of("field", "uri", "uri", uri, "conflictId", existing.getId()));
                });

        resource.setUri(uri);
        resource.setName(trimToNull(request.name()));
        resource.setDescription(trimToNull(request.description()));
        resource.setMimeType(trimToNull(request.mimeType()));
        resource.setStaticContent(staticContent ? request.content() : null);
        resource.setToolId(mapped ? tool.getId() : null);
        resource.setTtlMs(request.ttlMs());
        McpResource saved = resourceRepository.save(resource);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("uri", uri);
        detail.put("mode", staticContent ? "STATIC" : "TOOL");
        if (mapped) {
            detail.put("toolId", tool.getId());
            detail.put("toolName", overlayService.effectiveName(tool));
        }
        if (staticContent) {
            // 内容本身不记审计（可能很大且可能含样例数据），只记长度
            detail.put("contentLength", request.content().length());
        }
        auditService.record(AuditAction.RESOURCE_CHANGE, "server", server.getId(), detail);
        return toView(saved, effectiveToolNames(serverId));
    }

    @Transactional
    public void deleteResource(Long serverId, Long resourceId, AuthPrincipal principal) {
        McpServer server = accessGuard.requireManage(serverId, principal);
        McpResource resource = resourceRepository.findById(resourceId)
                .filter(existing -> serverId.equals(existing.getServerId()))
                .orElseThrow(() -> PlatformException.notFound("MCP Resource", resourceId));
        resourceRepository.delete(resource);
        auditService.record(AuditAction.RESOURCE_CHANGE, "server", server.getId(),
                Map.of("uri", resource.getUri() == null ? "-" : resource.getUri(), "deleted", true));
    }

    /** 供发布快照组装：解析 tool 映射为<b>生效名</b>，运行时按名查找。 */
    @Transactional(readOnly = true)
    public List<ResourceSnapshot> resourceSnapshots(McpServer server) {
        Map<Long, String> toolNames = effectiveToolNames(server.getId());
        List<ResourceSnapshot> snapshots = new ArrayList<>();
        for (McpResource resource : resourceRepository.findByServerIdOrderBySortOrderAscIdAsc(server.getId())) {
            String toolName = resource.getToolId() == null ? null : toolNames.get(resource.getToolId());
            if (resource.getToolId() != null && toolName == null) {
                // tool 被删或不属于本 Server：跳过，不要让一份坏配置让整个发布失败
                continue;
            }
            snapshots.add(new ResourceSnapshot(
                    resource.getUri(),
                    resource.getName(),
                    resource.getDescription(),
                    resource.getMimeType(),
                    resource.isStaticContent() ? resource.getStaticContent() : null,
                    toolName,
                    resource.getTtlMs()));
        }
        return snapshots;
    }

    // ---------------------------------------------------------------- Prompt

    @Transactional(readOnly = true)
    public List<ServerDtos.PromptView> prompts(Long serverId, AuthPrincipal principal) {
        accessGuard.requireRead(serverId, principal);
        return promptsOf(serverId);
    }

    @Transactional(readOnly = true)
    public List<ServerDtos.PromptView> promptsOf(Long serverId) {
        return promptRepository.findByServerIdOrderBySortOrderAscIdAsc(serverId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ServerDtos.PromptView savePrompt(Long serverId, Long promptId,
                                            ServerDtos.PromptRequest request,
                                            AuthPrincipal principal) {
        McpServer server = accessGuard.requireManage(serverId, principal);
        String name = requirePromptName(request.name());
        List<PromptSnapshot.Argument> arguments = requireArguments(request.arguments());
        requirePlaceholdersDeclared(request.template(), arguments);

        McpPrompt prompt = promptId == null
                ? new McpPrompt()
                : promptRepository.findById(promptId)
                        .filter(existing -> serverId.equals(existing.getServerId()))
                        .orElseThrow(() -> PlatformException.notFound("MCP Prompt", promptId));
        if (promptId == null) {
            prompt.setServerId(serverId);
            prompt.setSortOrder(nextPromptOrder(serverId));
        }
        promptRepository.findByServerIdAndName(serverId, name)
                .filter(existing -> !existing.getId().equals(prompt.getId()))
                .ifPresent(existing -> {
                    throw PlatformException.validation("该 Prompt 名已存在",
                            Map.of("field", "name", "name", name, "conflictId", existing.getId()));
                });

        prompt.setName(name);
        prompt.setTitle(trimToNull(request.title()));
        prompt.setDescription(trimToNull(request.description()));
        prompt.setTemplate(request.template());
        prompt.setArguments(arguments.isEmpty() ? null : Json.write(arguments));
        prompt.setTtlMs(request.ttlMs());
        McpPrompt saved = promptRepository.save(prompt);

        auditService.record(AuditAction.PROMPT_CHANGE, "server", server.getId(), Map.of(
                "name", name,
                "argumentCount", arguments.size(),
                "templateLength", request.template() == null ? 0 : request.template().length()));
        return toView(saved);
    }

    @Transactional
    public void deletePrompt(Long serverId, Long promptId, AuthPrincipal principal) {
        McpServer server = accessGuard.requireManage(serverId, principal);
        McpPrompt prompt = promptRepository.findById(promptId)
                .filter(existing -> serverId.equals(existing.getServerId()))
                .orElseThrow(() -> PlatformException.notFound("MCP Prompt", promptId));
        promptRepository.delete(prompt);
        auditService.record(AuditAction.PROMPT_CHANGE, "server", server.getId(),
                Map.of("name", prompt.getName() == null ? "-" : prompt.getName(), "deleted", true));
    }

    @Transactional(readOnly = true)
    public List<PromptSnapshot> promptSnapshots(McpServer server) {
        return promptRepository.findByServerIdOrderBySortOrderAscIdAsc(server.getId()).stream()
                .map(prompt -> new PromptSnapshot(
                        prompt.getName(),
                        prompt.getTitle(),
                        prompt.getDescription(),
                        prompt.getTemplate(),
                        readArguments(prompt.getArguments()),
                        prompt.getTtlMs()))
                .toList();
    }

    // ---------------------------------------------------------------- 校验

    static String requireUri(String raw) {
        String uri = trimToNull(raw);
        if (uri == null) {
            throw PlatformException.validation("Resource URI 不能为空", Map.of("field", "uri"));
        }
        if (!RESOURCE_URI.matcher(uri).matches()) {
            throw PlatformException.validation("Resource URI 必须带 scheme（如 mcp://crm-order/schema）",
                    Map.of("field", "uri", "uri", uri, "pattern", RESOURCE_URI.pattern()));
        }
        return uri;
    }

    static String requirePromptName(String raw) {
        String name = trimToNull(raw);
        if (name == null) {
            throw PlatformException.validation("Prompt 名不能为空", Map.of("field", "name"));
        }
        if (!PROMPT_NAME.matcher(name).matches()) {
            throw PlatformException.validation("Prompt 名只能包含字母、数字、下划线、点、连字符，且不超过 128 字符",
                    Map.of("field", "name", "name", name, "pattern", PROMPT_NAME.pattern()));
        }
        return name;
    }

    /**
     * 参数声明校验：名字必须合法、不能重名。
     *
     * <p>名字合法性不是洁癖——参数名要能原样写进 {@code {{...}}} 占位符里，
     * 声明一个含空格的名字等于声明了一个永远引用不到的参数。
     */
    static List<PromptSnapshot.Argument> requireArguments(List<ServerDtos.PromptArgumentRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<PromptSnapshot.Argument> arguments = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Object> problems = new LinkedHashMap<>();
        for (ServerDtos.PromptArgumentRequest argument : raw) {
            String name = trimToNull(argument.name());
            if (name == null) {
                problems.put("arguments", "参数名不能为空");
                continue;
            }
            if (!ARGUMENT_NAME.matcher(name).matches()) {
                problems.put("arguments[" + name + "]",
                        "参数名只能包含字母、数字、下划线、点、连字符，且不超过 64 字符");
                continue;
            }
            if (!seen.add(name)) {
                problems.put("arguments[" + name + "]", "参数名重复");
                continue;
            }
            arguments.add(new PromptSnapshot.Argument(name, trimToNull(argument.description()), argument.required()));
        }
        if (!problems.isEmpty()) {
            throw PlatformException.validation("Prompt 参数声明不合法：" + joinProblems(problems), problems);
        }
        return arguments;
    }

    /**
     * 模板里的占位符必须都声明过，且声明过的参数必须都被用到。
     *
     * <p>未声明就写进模板的占位符，运行时会被替换成空串——拼写错误 {@code {{oderId}}} 会变成
     * 线上提示词里一个沉默的空洞，而不是一次可定位的报错。这条校验把问题挪到保存时。
     *
     * <p>反方向（声明了却没用）同样拒绝：它通常意味着模板被改过但参数没同步清理，
     * 客户端会提示用户填一个对结果毫无影响的值。
     */
    static void requirePlaceholdersDeclared(String template, List<PromptSnapshot.Argument> arguments) {
        Set<String> placeholders = PromptTemplate.placeholders(template);
        Set<String> declared = new LinkedHashSet<>();
        for (PromptSnapshot.Argument argument : arguments) {
            declared.add(argument.name());
        }
        List<String> undeclared = placeholders.stream().filter(name -> !declared.contains(name)).toList();
        List<String> unused = declared.stream().filter(name -> !placeholders.contains(name)).toList();
        Map<String, Object> problems = new LinkedHashMap<>();
        if (!undeclared.isEmpty()) {
            problems.put("template", "模板里的占位符没有声明为参数：" + String.join(", ", undeclared));
        }
        if (!unused.isEmpty()) {
            problems.put("arguments", "以下参数声明了但没有在模板里使用：" + String.join(", ", unused));
        }
        if (!problems.isEmpty()) {
            throw PlatformException.validation("Prompt 模板与参数声明不一致：" + joinProblems(problems), problems);
        }
    }

    /**
     * 把逐字段的问题压成一句可读的摘要。
     *
     * <p>细节仍然完整保留在 {@code details} 里供前端逐字段标红，但 message 也必须能独立说清问题——
     * 日志与审计只打 message，如果那里只有一句「不一致」，排障时要再去翻响应体。
     */
    private static String joinProblems(Map<String, Object> problems) {
        return problems.entrySet().stream()
                .map(entry -> entry.getKey() + " → " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("；"));
    }

    // ---------------------------------------------------------------- 内部工具

    private static List<PromptSnapshot.Argument> readArguments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PromptSnapshot.Argument> parsed = Json.read(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<PromptSnapshot.Argument>>() {
                    });
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private McpTool requireToolOfServer(Long serverId, Long toolId) {
        return toolRepository.findById(toolId)
                .filter(tool -> serverId.equals(tool.getServerId()))
                .orElseThrow(() -> PlatformException.validation("映射的 tool 不属于该 Server",
                        Map.of("field", "toolId", "toolId", toolId, "serverId", serverId)));
    }

    /** toolId → 当前生效名。资源映射按 id 存、按名解析，所以名字被覆盖改掉后映射依然有效。 */
    private Map<Long, String> effectiveToolNames(Long serverId) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (McpTool tool : toolRepository.findByServerIdOrderBySortOrderAsc(serverId)) {
            names.put(tool.getId(), overlayService.effectiveName(tool));
        }
        return names;
    }

    private ServerDtos.ResourceView toView(McpResource resource, Map<Long, String> toolNames) {
        return new ServerDtos.ResourceView(
                resource.getId(),
                resource.getUri(),
                resource.getName(),
                resource.getDescription(),
                resource.getMimeType(),
                resource.getStaticContent(),
                resource.getToolId(),
                resource.getToolId() == null ? null : toolNames.get(resource.getToolId()),
                resource.getTtlMs(),
                resource.getSortOrder());
    }

    private ServerDtos.PromptView toView(McpPrompt prompt) {
        List<ServerDtos.PromptArgumentRequest> arguments = readArguments(prompt.getArguments()).stream()
                .map(argument -> new ServerDtos.PromptArgumentRequest(
                        argument.name(), argument.description(), argument.required()))
                .toList();
        return new ServerDtos.PromptView(
                prompt.getId(),
                prompt.getName(),
                prompt.getTitle(),
                prompt.getDescription(),
                prompt.getTemplate(),
                arguments,
                prompt.getTtlMs(),
                prompt.getSortOrder());
    }

    private int nextResourceOrder(Long serverId) {
        Optional<McpResource> last = resourceRepository.findByServerIdOrderBySortOrderAscIdAsc(serverId).stream()
                .reduce((first, second) -> second);
        return last.map(resource -> resource.getSortOrder() + 1).orElse(0);
    }

    private int nextPromptOrder(Long serverId) {
        Optional<McpPrompt> last = promptRepository.findByServerIdOrderBySortOrderAscIdAsc(serverId).stream()
                .reduce((first, second) -> second);
        return last.map(prompt -> prompt.getSortOrder() + 1).orElse(0);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
