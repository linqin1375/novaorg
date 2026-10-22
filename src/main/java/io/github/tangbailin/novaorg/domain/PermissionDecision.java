package io.github.tangbailin.novaorg.domain;

/** The result of asking "may this role call this tool right now?" */
public record PermissionDecision(boolean allowed, String reason) {

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(true, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }
}
