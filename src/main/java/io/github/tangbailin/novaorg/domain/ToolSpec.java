package io.github.tangbailin.novaorg.domain;

import java.util.List;
import java.util.Map;

/**
 * A tool specification, shaped after the MCP tool contract
 * ({@code tools/list} returns name + description + JSON-Schema, {@code tools/call} takes a
 * JSON argument object). Keeping our registry MCP-shaped means the same tools can later be
 * exported over a real MCP server without rewriting call sites.
 */
public record ToolSpec(
        String name,
        String description,
        /** JSON-Schema-ish description of the arguments, used for validation and prompting. */
        Map<String, String> parameters,
        List<String> requiredParameters,
        Permission requiredPermission,
        RiskLevel risk,
        /** Idempotent tools are safe to retry; non-idempotent ones need a dedup key. */
        boolean idempotent
) {
}
