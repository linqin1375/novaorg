package io.github.tangbailin.novaorg.knowledge;

import io.github.tangbailin.novaorg.domain.Citation;
import java.util.*;
import java.util.regex.Pattern;

/** Stable chunk references survive multiple retrieval calls; existence is not semantic entailment. */
public final class EvidenceReferences {
    private static final Pattern STABLE=Pattern.compile("\\[([A-Za-z0-9_.-]+\\.md)#(\\d+)]");
    private static final Pattern NUMBER=Pattern.compile("\\[(\\d+)]");
    private EvidenceReferences() {}
    public static String id(Citation c) { return c.docId()+"#"+c.chunkIndex(); }
    public record Check(List<String> docs,int invalid,int valid) {}
    public static Check check(String answer,List<Citation> evidence,boolean stableOnly) {
        var docs=new ArrayList<String>();int invalid=0,valid=0;
        var stable=STABLE.matcher(answer==null?"":answer);
        var allowed=new HashMap<String,String>();evidence.forEach(c->allowed.put(id(c),c.docId()));
        while(stable.find()) {
            String key=stable.group(1)+"#"+stable.group(2);
            if(!allowed.containsKey(key)) invalid++;else { valid++;docs.add(allowed.get(key)); }
        }
        var numbers=NUMBER.matcher(answer==null?"":answer);
        while(numbers.find()) {
            int n;try { n=Integer.parseInt(numbers.group(1)); } catch(NumberFormatException e) { invalid++;continue; }
            if(stableOnly || n<1 || n>evidence.size()) invalid++;
            else { valid++;docs.add(evidence.get(n-1).docId()); }
        }
        return new Check(List.copyOf(docs),invalid,valid);
    }
}
