package io.github.tangbailin.novaorg.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads the company's documentation from the classpath and indexes it.
 *
 * <p>Chunking is heading-aware: a chunk never spans two {@code ##} sections, because mixing a
 * refund rule into a shipping rule is the fastest way to make a knowledge assistant lie.
 */
public final class ClasspathKnowledgeBase implements KnowledgeBase {

    public static final String DEFAULT_LOCATION = "classpath*:knowledge/**/*.md";
    private static final int MAX_CHUNK_CHARS = 420;
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^namespace:\\s*(\\S+)\\s*$");
    private static final Pattern TITLE = Pattern.compile("(?m)^title:\\s*(.+?)\\s*$");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");

    private final InMemoryHybridIndex index;

    public ClasspathKnowledgeBase(EmbeddingPort embeddingPort) {
        this(embeddingPort, DEFAULT_LOCATION);
    }

    public ClasspathKnowledgeBase(EmbeddingPort embeddingPort, String locationPattern) {
        this(embeddingPort, locationPattern, RankFusion.DEFAULT_K, 20, 0.0);
    }

    public ClasspathKnowledgeBase(EmbeddingPort embeddingPort, String locationPattern,
                                  int rrfK, int rerankDepth, double rerankWeight) {
        this(embeddingPort, locationPattern, rrfK, rerankDepth, rerankWeight,
                java.util.EnumSet.allOf(InMemoryHybridIndex.Channel.class));
    }

    /** 消融实验入口：可以只开部分召回通道，用来确认每一路都值得保留。 */
    public ClasspathKnowledgeBase(EmbeddingPort embeddingPort, String locationPattern,
                                  int rrfK, int rerankDepth, double rerankWeight,
                                  java.util.Set<InMemoryHybridIndex.Channel> channels) {
        this.index = new InMemoryHybridIndex(embeddingPort, rrfK, rerankDepth, rerankWeight, channels);
        index.add(load(locationPattern));
    }

    static List<DocumentChunk> load(String locationPattern) {
        List<DocumentChunk> chunks = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(locationPattern);
            for (Resource resource : resources) {
                String raw;
                try(var input=resource.getInputStream()) { raw=new String(input.readAllBytes(),StandardCharsets.UTF_8); }
                String fileName = resource.getFilename() == null ? "doc" : resource.getFilename();
                chunks.addAll(chunk(fileName, raw));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load knowledge base: " + locationPattern, e);
        }
        return chunks;
    }

    static List<DocumentChunk> chunk(String fileName, String raw) {
        Matcher ns = NAMESPACE.matcher(raw);
        String namespace = ns.find() ? ns.group(1) : "general";
        Matcher ti = TITLE.matcher(raw);
        String docTitle = ti.find() ? ti.group(1) : fileName.replace(".md", "");

        List<DocumentChunk> out = new ArrayList<>();
        String body = raw;
        int frontMatterEnd = raw.indexOf("\n---", 3);
        if (raw.startsWith("---") && frontMatterEnd > 0) {
            body = raw.substring(raw.indexOf('\n', frontMatterEnd + 1) + 1);
        }

        String section = null;
        StringBuilder buffer = new StringBuilder();
        int[] indexCounter = {0};

        for (String line : body.split("\n")) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(out, fileName, docTitle, namespace, section, buffer, indexCounter);
                section = heading.group(1);
                continue;
            }
            if (line.isBlank()) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                continue;
            }
            buffer.append(line).append('\n');
            if (buffer.length() >= MAX_CHUNK_CHARS) {
                flush(out, fileName, docTitle, namespace, section, buffer, indexCounter);
            }
        }
        flush(out, fileName, docTitle, namespace, section, buffer, indexCounter);
        return out;
    }

    private static void flush(List<DocumentChunk> out, String fileName, String docTitle,
                              String namespace, String section,
                              StringBuilder buffer, int[] indexCounter) {
        String text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String title = section == null ? docTitle : docTitle + " · " + section;
        out.add(new DocumentChunk(fileName, title, namespace, indexCounter[0]++, text));
    }

    @Override
    public List<SearchHit> search(String query, List<String> namespaces, int topK) {
        return index.search(query, namespaces, topK);
    }

    /** 供消融实验用：关掉精排，只看召回阶段的排序质量。 */
    public List<SearchHit> search(String query, List<String> namespaces, int topK, boolean rerank) {
        return index.search(query, namespaces, topK, rerank);
    }

    @Override
    public int chunkCount() {
        return index.size();
    }

    @Override
    public java.util.List<String> namespaces() {
        return index.namespaces();
    }

    @Override
    public String embeddingId() {
        return index.embeddingPort().id();
    }
}
