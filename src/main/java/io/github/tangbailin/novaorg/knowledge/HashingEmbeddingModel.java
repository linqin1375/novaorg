package io.github.tangbailin.novaorg.knowledge;

import java.util.Locale;

/**
 * Deterministic local embedding via feature hashing (a.k.a. the hashing trick).
 *
 * <p>Why ship this as the default instead of calling a provider?
 * <ul>
 *   <li>the evaluation suite must be <b>reproducible</b> - a real embedding API returns
 *       slightly different rankings over time, which makes regression tests flaky;</li>
 *   <li>the project must boot with <b>zero credentials</b> so anyone can clone and run it;</li>
 *   <li>it keeps the retrieval pipeline honest: swapping in a real model is a one-line change
 *       and the metrics comparison then means something.</li>
 * </ul>
 * Quality is obviously below a real model, which is exactly why the API reports the
 * embedding implementation id next to the metric.
 */
public final class HashingEmbeddingModel implements EmbeddingPort {

    private final int dimensions;

    public HashingEmbeddingModel() {
        this(512);
    }

    public HashingEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public String id() {
        return "local-hashing-" + dimensions + "d";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimensions];
        var tokens = Tokenizer.tokens(text);
        for (String token : tokens) {
            int h = token.hashCode();
            int idx = Math.floorMod(h, dimensions);
            // signed hashing keeps the vector unbiased instead of only accumulating mass
            float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
            v[idx] += sign * weight(token);
        }
        return normalize(v);
    }

    /** Longer tokens carry more meaning than single CJK characters. */
    private float weight(String token) {
        int len = token.length();
        if (len >= 4) {
            return 1.6f;
        }
        if (len == 3) {
            return 1.3f;
        }
        if (len == 2) {
            return 1.0f;
        }
        String t = token.toLowerCase(Locale.ROOT);
        if ("的".equals(t) || "了".equals(t) || "和".equals(t) || "是".equals(t)) {
            return 0.2f;
        }
        return 0.6f;
    }

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += (double) f * f;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        if(a.length!=b.length) throw new IllegalArgumentException("Embedding dimensions differ");
        double dot = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
