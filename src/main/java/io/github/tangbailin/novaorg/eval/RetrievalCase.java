package io.github.tangbailin.novaorg.eval;

import java.util.List;

/**
 * 一条检索评测用例：一个真实口吻的查询，加上"哪份制度能回答它"的地面真值。
 *
 * <p>和 {@link EvalCase} 的区别：那个测的是整条 Agent 链路（派单、工具、审批），
 * 依赖模型输出，在 demo 模式下只有参考意义；这个只测<b>检索</b>，
 * 输入端和输出端都是确定的，所以它的数字是真正可复现、可做前后对照的。
 */
public record RetrievalCase(
        String id,
        String query,
        /** 能回答该查询的文档（文件名）。命中任意一个即算召回。 */
        List<String> expectDocs,
        String category,
        /** easy / medium / hard —— hard 指"纯关键词检索一定失败"的语义改写。 */
        String difficulty
) {
}
