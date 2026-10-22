package io.github.tangbailin.novaorg.domain;

import java.util.List;
import java.util.Set;

/**
 * A role is a "job description" for an agent: what it is accountable for, which knowledge
 * it may read, which tools it may call, what it must hand back, and how much autonomy it has.
 *
 * <p>This is the single source of truth for both <b>enforcement</b> (RoleGuard) and
 * <b>evaluation</b> (was the work given to the right role at all?).
 */
public record Role(
        String id,
        String name,
        String department,
        String mission,
        String systemPrompt,
        Set<Permission> permissions,
        Set<String> knowledgeNamespaces,
        List<String> deliverableKeys,
        /** How many times this role may be re-tried before the task is escalated. */
        int maxRetries
) {
    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
