package io.github.tangbailin.novaorg.api;

import io.github.tangbailin.novaorg.eval.EvalReport;
import io.github.tangbailin.novaorg.eval.EvalRunner;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner evalRunner;

    public EvalController(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @GetMapping("/latest")
    public ResponseEntity<?> latest() {
        EvalReport report = evalRunner.latest();
        if (report == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "NO_REPORT",
                    "hint", "尚未生成评测报告，运行 ./mvnw -q -DskipTests compile exec:java 或 bash scripts/run-eval.sh"));
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/cases")
    public List<String> cases() {
        return evalRunner.loadCases("eval/cases.json").stream()
                .map(c -> c.id())
                .toList();
    }
}
