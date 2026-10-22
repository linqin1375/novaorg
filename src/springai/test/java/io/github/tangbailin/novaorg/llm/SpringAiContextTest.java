package io.github.tangbailin.novaorg.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

/** Builds the real optional application graph without calling an external provider. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:adapter-context;DB_CLOSE_DELAY=-1",
        "NOVAORG_OPERATOR_PASSWORD=test-operator-1234","NOVAORG_REVIEWER_PASSWORD=test-reviewer-1234","NOVAORG_ADMIN_PASSWORD=test-admin-1234",
        "novaorg.llm.springai.enabled=true","spring.ai.model.chat=openai",
        "spring.ai.openai.api-key=test-dummy-not-a-real-key","spring.ai.openai.base-url=http://127.0.0.1:1"})
class SpringAiContextTest {
    @Autowired ApplicationContext context;
    @Test void optionalAdapterIsTheOnlyLlmPort() {
        assertThat(context.getBeansOfType(LlmPort.class)).hasSize(1);
        assertThat(context.getBean(LlmPort.class)).isInstanceOf(SpringAiLlmAdapter.class);
    }
}
