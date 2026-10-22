package io.github.tangbailin.novaorg.api;

import io.github.tangbailin.novaorg.org.RoleCatalog;
import io.github.tangbailin.novaorg.org.ToolCatalog;
import io.github.tangbailin.novaorg.runtime.WorkflowEngine;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OrgController {

    private final RoleCatalog roleCatalog;
    private final ToolCatalog toolCatalog;
    private final WorkflowEngine engine;
    private final TaskRunService service;
    private final ViewMapper mapper;

    public OrgController(RoleCatalog roleCatalog, ToolCatalog toolCatalog, WorkflowEngine engine,
                         TaskRunService service, ViewMapper mapper) {
        this.roleCatalog = roleCatalog;
        this.toolCatalog = toolCatalog;
        this.engine = engine;
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/org/roles")
    public List<Dtos.RoleView> roles() {
        return roleCatalog.all().stream().map(mapper::role).toList();
    }

    @GetMapping("/org/tools")
    public List<Dtos.ToolView> tools() {
        return toolCatalog.all().stream().map(mapper::tool).toList();
    }

    @GetMapping("/org/chart")
    public Dtos.OrgChartView chart() {
        var raw = engine.orgChart();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> departments =
                (List<java.util.Map<String, Object>>) raw.get("departments");
        List<Dtos.DepartmentView> views = departments.stream().map(d -> {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> roles =
                    (List<java.util.Map<String, Object>>) d.get("roles");
            return new Dtos.DepartmentView(
                    String.valueOf(d.get("department")),
                    roles.stream().map(r -> new Dtos.RoleView(
                            String.valueOf(r.get("id")),
                            String.valueOf(r.get("name")),
                            String.valueOf(d.get("department")),
                            String.valueOf(r.get("mission")),
                            castList(r.get("permissions")),
                            castList(r.get("knowledgeNamespaces")),
                            castList(r.get("tools")),
                            0)).toList());
        }).toList();
        return new Dtos.OrgChartView(views, roleCatalog.all().size(), toolCatalog.all().size());
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }

    @GetMapping("/metrics/roles")
    public List<Dtos.RoleMetricsView> roleMetrics() {
        return service.roleMetrics();
    }

    @GetMapping("/system")
    public Dtos.SystemView system() {
        return mapper.system();
    }
}
