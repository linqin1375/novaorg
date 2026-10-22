package io.github.tangbailin.novaorg.tools;

import java.util.*;

/** Synthetic multi-order sandbox. No real payment or customer service is connected. */
public final class BusinessSystems {
    public static final String DEMO_ORDER_ID="A100";
    public record Order(String orderId,long paidFen,long refundedFen,int promisedHours,int actualHours,
                        String customerId,int claimsLast30Days,String category,String fulfillmentStatus) {
        public Order(String id,long paid,long refunded,int promised,int actual,String customer,int claims,String category) {
            this(id,paid,refunded,promised,actual,customer,claims,category,"UNSHIPPED");
        }
        public int delayHours() { return Math.max(0,actualHours-promisedHours); }
        public boolean highRisk() { return claimsLast30Days>=3; }
        public String paidYuan() { return String.format(java.util.Locale.ROOT,"%.2f",paidFen/100.0); }
    }
    private final Map<String,Order> orders=new LinkedHashMap<>();
    private final Map<String,Long> amounts=new HashMap<>();
    private final Map<String,String> receiptOrders=new HashMap<>();
    private final String defaultId;
    private io.github.tangbailin.novaorg.persistence.RefundLedger ledger;
    public BusinessSystems() {
        this("A100",12900,48,96,"C-8837",1,"数码");
        add(new Order("A101",25900,0,24,100,"C-8838",0,"家居"));
        add(new Order("A102",49900,0,12,72,"C-8839",4,"生鲜"));
        add(new Order("A103",8900,0,48,12,"C-8840",0,"图书","DELIVERED"));
        add(new Order("A104",200000,0,24,150,"C-8841",2,"家电"));
    }
    public BusinessSystems(io.github.tangbailin.novaorg.persistence.RefundLedger ledger) { this();this.ledger=ledger; }
    public BusinessSystems(String id,long paid,int promised,int actual,String customer,int claims,String category) {
        defaultId=id;add(new Order(id,paid,0,promised,actual,customer,claims,category));
    }
    private void add(Order order) { orders.put(order.orderId(),order); }
    public synchronized Optional<Order> findOrder(String id) {
        if(ledger!=null) return ledger.findOrder(id);
        return Optional.ofNullable(orders.get(id));
    }
    public Order requireOrder(String id) { return findOrder(id).orElseThrow(()->new IllegalArgumentException("ORDER_NOT_FOUND")); }
    public Optional<Order> findCustomer(String id) { return orders.values().stream().filter(o->o.customerId().equals(id)).findFirst(); }
    private Order initial() { return requireOrder(defaultId); }
    public String orderId() { return defaultId; }
    public long orderAmountFen() { return initial().paidFen(); }
    public String orderAmountYuan() { return initial().paidYuan(); }
    public int promisedHours() { return initial().promisedHours(); }
    public int actualHours() { return initial().actualHours(); }
    public int delayHours() { return initial().delayHours(); }
    public String customerId() { return initial().customerId(); }
    public int customerClaimsLast30Days() { return initial().claimsLast30Days(); }
    public String productCategory() { return initial().category(); }
    public boolean highRiskClaimant() { return initial().highRisk(); }
    public synchronized boolean executeRefund(String key,long fen) { return executeRefund(key,defaultId,fen); }
    public synchronized boolean executeRefund(String key,String id,long fen) {
        if(ledger!=null) return ledger.execute(key,id,fen);
        var order=requireOrder(id);
        if(fen<=0) throw new IllegalArgumentException("INVALID_AMOUNT");
        if(amounts.containsKey(key)) {
            if(amounts.get(key)!=fen || !id.equals(receiptOrders.get(key))) throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
            return false;
        }
        if(fen>order.paidFen()-order.refundedFen()) throw new IllegalArgumentException("REFUND_LIMIT");
        amounts.put(key,fen);receiptOrders.put(key,id);
        orders.put(id,new Order(id,order.paidFen(),order.refundedFen()+fen,order.promisedHours(),order.actualHours(),
                order.customerId(),order.claimsLast30Days(),order.category(),order.fulfillmentStatus()));
        return true;
    }
    public synchronized long refundedFen() { return ledger==null?amounts.values().stream().mapToLong(Long::longValue).sum():ledger.total(); }
    public synchronized int refundCount() { return ledger==null?amounts.size():ledger.count(); }
}
