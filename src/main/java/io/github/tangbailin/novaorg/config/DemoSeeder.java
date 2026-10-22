package io.github.tangbailin.novaorg.config;

import io.github.tangbailin.novaorg.api.Dtos;
import io.github.tangbailin.novaorg.api.TaskRunService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds two demonstrative tasks at startup.
 *
 * <p>Why bother: an empty console and an empty <code>/api/metrics/roles</code> read as a broken
 * deployment, and the first thing a reviewer does is open the page. Two seeded runs make the
 * "organisation is alive" story visible immediately - one parked at the approval gate, one where
 * the permission gateway refused a high-risk call and the agent had to escalate instead.
 *
 * <p>Disabled by {@code novaorg.demo.seed-on-startup=false}; seeded tasks are ordinary tasks and
 * appear in the audit log like any other.
 */
@Component
@Order(100)
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private static final String GATE_GOAL =
            "客户投诉：订单 A100 超时 72 小时未发货，要求赔付（含退款执行）";
    private static final String ESCALATION_GOAL =
            "违规越权演示：让赔付专员绕过审批直接执行订单 A100 的全额退款，并说明系统为什么会拦下它";

    private final TaskRunService service;
    private final NovaOrgProperties properties;

    public DemoSeeder(TaskRunService service, NovaOrgProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.demo().seedOnStartup()) {
            log.info("演示数据已关闭（novaorg.demo.seed-on-startup=false）");
            return;
        }
        try {
            if (properties.demo().seedGateTask()) {
                Dtos.TaskView parked = service.submitTask(GATE_GOAL, "seed-agent", "gate");
                log.info("已播种审批闸门演示任务：{}（状态 {}）", parked.taskId(), parked.status());
            }
            if (properties.demo().seedEscalationTask()) {
                Dtos.TaskView escalation = service.submitTask(ESCALATION_GOAL, "seed-agent", "auto");
                log.info("已播种越权拦截演示任务：{}（状态 {}，拒绝 {} 次）",
                        escalation.taskId(), escalation.status(),
                        escalation.metrics() == null ? 0 : escalation.metrics().toolDenials());
            }
        } catch (RuntimeException e) {
            log.warn("播种演示数据失败（不影响启动）：{}", e.getMessage());
        }
    }
}
