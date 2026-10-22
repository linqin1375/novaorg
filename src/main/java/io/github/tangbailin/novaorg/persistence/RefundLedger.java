package io.github.tangbailin.novaorg.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

/** Simulated money only. The receipt and balance update share the workflow transaction. */
public final class RefundLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public RefundLedger(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public boolean execute(String key, String orderId, long fen) {
        record Outcome(boolean moved, String error) {}
        Outcome outcome=tx.execute(status -> {
            var orders=jdbc.queryForList("SELECT paid_fen,refunded_fen FROM simulated_orders WHERE order_id=? FOR UPDATE",orderId);
            if(orders.isEmpty() || fen<=0) return new Outcome(false,"订单不存在或退款金额不合法");
            var receipts=jdbc.queryForList("SELECT order_id,amount_fen FROM refund_receipts WHERE business_key=? FOR UPDATE",key);
            if(!receipts.isEmpty()) {
                var receipt=receipts.get(0);
                if(!orderId.equals(receipt.get("order_id"))) return new Outcome(false,"IDEMPOTENCY_CONFLICT: 订单不同");
                if(((Number)receipt.get("amount_fen")).longValue()!=fen) return new Outcome(false,"IDEMPOTENCY_CONFLICT: 同一业务键的金额不同");
                return new Outcome(false,null);
            }
            var order=orders.get(0);
            long paid=((Number)order.get("paid_fen")).longValue(), refunded=((Number)order.get("refunded_fen")).longValue();
            if(fen>paid-refunded) return new Outcome(false,"REFUND_LIMIT: 累计退款不能超过实付金额");
            jdbc.update("INSERT INTO refund_receipts(business_key,order_id,amount_fen,created_at) VALUES (?,?,?,?)",key,orderId,fen,Instant.now().toString());
            jdbc.update("UPDATE simulated_orders SET refunded_fen=refunded_fen+? WHERE order_id=?",fen,orderId);
            return new Outcome(true,null);
        });
        // A business refusal is not a transaction failure: persist its audit/task outcome.
        // SQL/connection failures still throw inside the callback and roll back all writes.
        if(outcome==null) throw new IllegalStateException("Missing ledger result");
        if(outcome.error()!=null) throw new IllegalArgumentException(outcome.error());
        return outcome.moved();
    }
    public long total() { return jdbc.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM refund_receipts",Long.class); }
    public int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM refund_receipts",Integer.class); }
    public java.util.Optional<io.github.tangbailin.novaorg.tools.BusinessSystems.Order> findOrder(String id) {
        var rows=jdbc.query("SELECT * FROM simulated_orders WHERE order_id=?",(rs,n)->
            new io.github.tangbailin.novaorg.tools.BusinessSystems.Order(rs.getString("order_id"),rs.getLong("paid_fen"),
                rs.getLong("refunded_fen"),rs.getInt("promised_hours"),rs.getInt("actual_hours"),
                rs.getString("customer_id"),rs.getInt("claims_30d"),rs.getString("category"),rs.getString("fulfillment_status")),id);
        return rows.stream().findFirst();
    }
}
