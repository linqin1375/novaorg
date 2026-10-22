package io.github.tangbailin.novaorg.knowledge;

import java.util.List;

/** 检索入口。每一次检索都必须由调用方角色可读的命名空间限定。 */
public interface KnowledgeBase {

    List<SearchHit> search(String query, List<String> namespaces, int topK);

    int chunkCount();

    /**
     * 语料里实际存在的全部命名空间。
     *
     * <p>只给两个场景用：离线评测（要全库检索才能衡量召回）、运维诊断。
     * <b>业务代码不允许用它</b>——那等于把知识侧的最小权限绕过去了。
     */
    List<String> namespaces();

    String embeddingId();
}
