package io.github.tangbailin.novaorg.tools;

import java.util.Optional;
import java.util.regex.Pattern;

/** Extract explicit sandbox identifiers only; never invent a default order for a live complaint. */
public final class OrderReference {
    private static final Pattern ID=Pattern.compile("(?i)(?<![A-Z0-9])([A-Z]{1,4}[0-9]{3,12})(?![A-Z0-9])");
    private OrderReference() {}
    public static Optional<String> extract(String text) {
        var matcher=ID.matcher(text==null?"":text);
        return matcher.find()?Optional.of(matcher.group(1).toUpperCase()):Optional.empty();
    }
}
