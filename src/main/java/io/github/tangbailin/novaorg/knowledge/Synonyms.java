package io.github.tangbailin.novaorg.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域同义词 / 概念归一化表（客服与售后场景）。
 *
 * <p>解决的问题：客诉文本和制度文本用的是两套词。「钱能退回来吗」「想撤销这个单子」
 * 和制度里的「退款」在字面上没有任何重叠，BM25 一条都检索不到；而 feature hashing
 * 只是把词面哈希一遍，本质上仍然是词面匹配，救不了这个问题。
 *
 * <p>做法：把每个说法映射到一个概念标记（如 {@code #REFUND}），在向量通道里额外注入
 * 概念标记。于是「退钱」和「退款」在向量空间里共享 {@code #REFUND} 这一维，余弦相似度
 * 立刻区分得开。这是离线的、确定性的、可解释的语义增强——不是 embedding 模型的替代品，
 * 但在没有 API key 的场景下，它让「混合检索」的两个通道真正互补：
 * <ul>
 *   <li>BM25 通道只看原词，保证订单号、条款号、商品名的精确命中；</li>
 *   <li>向量通道额外看概念，保证口语改写也能召回。</li>
 * </ul>
 *
 * <p>如果配置了真实的 embedding 服务（{@code novaorg.embedding.mode=api}），这张表仍然
 * 有用：它同时充当查询扩展词典。
 */
public final class Synonyms {

    /** 概念 id -> 该概念的所有说法。顺序无所谓，匹配时长词优先。 */
    private static final Map<String, List<String>> CONCEPTS = new LinkedHashMap<>();

    static {
        concept("REFUND", "退款", "退钱", "退费", "退给我", "返还", "退单", "把钱退", "退回来", "退掉钱");
        concept("CANCEL", "撤销", "取消订单", "取消这个单", "撤单", "不想要了", "退订");
        concept("COMPENSATION", "赔付", "赔偿", "补偿", "补助", "赔钱", "赔我", "补我", "补偿金", "赔款");
        concept("RETURN", "退货", "退回", "退掉", "寄回", "不要了", "退回去");
        concept("TIMEOUT", "超时", "延迟", "晚了", "迟迟不发", "拖着不发", "不发货", "一直不发",
                "一个星期没发", "还没发", "逾期", "超过承诺时间");
        concept("PRICE_PROTECTION", "价保", "保价", "降价", "差价", "刚买就降", "价格保护", "补差价");
        concept("WARRANTY", "三包", "保修", "质保", "包修", "包换", "坏了", "维修", "修一下", "开胶", "故障");
        concept("WARRANTY_SCOPE", "进水", "摔坏", "摔了", "人为损坏", "保内", "保外", "保修范围", "免费修",
                "保修期", "过了保修", "在保");
        concept("LOGISTICS", "物流", "快递", "配送", "派送", "揽收", "签收", "运单", "驿站", "送达");
        concept("DAMAGE", "破损", "损坏", "压坏", "碎了", "漏了", "变质", "缺件", "少件", "丢了");
        concept("FAKE", "假货", "假一赔十", "假冒", "翻新", "虚假宣传", "货不对板", "不是正品", "山寨");
        concept("CAMPAIGN", "活动", "营销", "拉新", "优惠券", "券", "补贴", "满减", "促销", "触达", "推送");
        concept("BUDGET", "预算", "成本", "费用", "花钱", "开销", "支出", "核销");
        concept("PROFIT", "毛利", "赚", "净利润", "亏", "踩线", "红线", "单均", "履约成本");
        concept("METRIC", "指标", "转化率", "退款率", "赔付率", "退货率", "数据", "下降", "环比", "同比", "归因");
        concept("AUDIT", "审计", "日志", "留痕", "记录", "可追溯", "回放", "台账");
        concept("COMPLIANCE", "合规", "违规", "越权", "权限", "授权", "监管", "条款", "消保法", "三包规定");
        concept("DUAL_CONTROL", "双人", "四眼", "两个人", "另一个人", "复核", "自己批自己", "自己点",
                "按钮", "操作权限", "能不能自己", "擅自");
        concept("APPROVAL", "审批", "审核", "批准", "签字", "放行", "复核");
        concept("ESCALATION", "升级", "上报", "几级", "一级", "二级", "三级", "通知上级", "转交");
        concept("RISK", "风险", "欺诈", "羊毛党", "刷单", "异常", "套利", "恶意");
        concept("SLA", "时效", "承诺时间", "多久", "几天", "48小时", "72小时", "履约", "服务标准");
        concept("INVOICE", "发票", "开票", "抬头", "税号", "报销凭证");
        concept("ACCOUNT", "账号", "账户", "登录", "实名", "封号", "冻结");
    }

    /** 长词优先匹配，避免「退款」被「退」抢先匹配。 */
    private static final List<Map.Entry<String, String>> SURFACE = buildSurface();

    private Synonyms() {
    }

    private static void concept(String id, String... surfaces) {
        CONCEPTS.put(id, List.of(surfaces));
    }

    private static List<Map.Entry<String, String>> buildSurface() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : CONCEPTS.entrySet()) {
            for (String s : e.getValue()) {
                out.add(Map.entry(s, e.getKey()));
            }
        }
        out.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        return List.copyOf(out);
    }

    /** 文本中命中的概念标记，形如 {@code #REFUND}。用于注入向量。 */
    public static List<String> conceptTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : SURFACE) {
            if (lower.contains(e.getKey())) {
                found.add("#" + e.getValue());
            }
        }
        // 「退」+「款」这类被标点/空格切开的说法，用字符级兜底再扫一遍
        String squeezed = lower.replaceAll("[\\s\\p{Punct}，。！？；：、（）【】「」《》…—]+", "");
        for (Map.Entry<String, String> e : SURFACE) {
            if (e.getKey().length() >= 2 && squeezed.contains(e.getKey())) {
                found.add("#" + e.getValue());
            }
        }
        return List.copyOf(found);
    }

    /** 查询扩展：把命中的概念的全部说法追加到查询里，供真实 embedding 服务使用。 */
    public static String expand(String query) {
        List<String> concepts = conceptTokens(query);
        if (concepts.isEmpty()) {
            return query;
        }
        StringBuilder sb = new StringBuilder(query);
        sb.append(' ');
        for (String c : concepts) {
            List<String> words = CONCEPTS.get(c.substring(1));
            if (words != null) {
                sb.append(String.join(" ", words)).append(' ');
            }
        }
        return sb.toString();
    }

    public static int conceptCount() {
        return CONCEPTS.size();
    }

    public static int surfaceCount() {
        return SURFACE.size();
    }

    public static Set<String> conceptIds() {
        return CONCEPTS.keySet();
    }
}
