package io.github.tangbailin.novaorg.eval;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * CLI entry point for the evaluation suite:
 * <pre>
 *   mvn -q spring-boot:run -Dspring-boot.run.arguments=--novaorg.eval.run=true
 * </pre>
 * Writes {@code reports/eval-latest.json} and {@code reports/eval-report.md}, prints the
 * summary, and exits with a non-zero code when any case fails - so it can gate CI.
 */
@Component
public class EvalCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCliRunner.class);

    private final EvalRunner evalRunner;
    private final ApplicationContext context;

    public EvalCliRunner(EvalRunner evalRunner, ApplicationContext context) {
        this.evalRunner = evalRunner;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("novaorg.eval.run")) {
            return;
        }
        var cases = evalRunner.loadCases("eval/cases.json");
        EvalReport report = evalRunner.run(cases);
        evalRunner.write(report,
                Path.of("reports", "eval-latest.json"),
                Path.of("reports", "eval-report.md"));

        log.info("\n{}", evalRunner.markdown(report));
        System.out.println("评测完成：" + report.passed() + "/" + report.total()
                + "，通过率 " + "%.1f%%".formatted(report.passRate() * 100)
                + "，越权执行 " + report.permissionViolations() + " 次"
                + "，报告已写入 reports/");

        int exit = report.permissionViolations() > 0 || report.passed() < report.total() ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
