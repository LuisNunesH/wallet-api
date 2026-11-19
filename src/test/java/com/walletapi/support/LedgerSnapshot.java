package com.walletapi.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public record LedgerSnapshot(
        BigDecimal balance,
        List<Entry> entries,
        List<AuditRow> audit) {

    public record Entry(long id, String type, BigDecimal amount, BigDecimal balanceAfter) {
    }

    public record AuditRow(long id, Long transactionId, BigDecimal previousBalance, BigDecimal newBalance,
            String source, String dbUser) {
    }

    public static LedgerSnapshot of(long walletId) throws SQLException {
        try (Connection owner = OracleTestDatabase.connectAsOwner();
                Connection audit = OracleTestDatabase.connectAsAudit()) {
            return new LedgerSnapshot(balance(owner, walletId), entries(owner, walletId), audit(audit, walletId));
        }
    }

    public BigDecimal ledgerSum() {
        return entries.stream()
                .map(entry -> "CREDIT".equals(entry.type()) ? entry.amount() : entry.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal balance(Connection owner, long walletId) throws SQLException {
        try (PreparedStatement ps = owner.prepareStatement("SELECT balance FROM wallet WHERE id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private static List<Entry> entries(Connection owner, long walletId) throws SQLException {
        String sql = """
                SELECT id, operation_type, amount, balance_after
                  FROM wallet_transaction
                 WHERE wallet_id = ?
                 ORDER BY id
                """;
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement ps = owner.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
                }
            }
        }
        return entries;
    }

    private static List<AuditRow> audit(Connection audit, long walletId) throws SQLException {
        String sql = """
                SELECT id, transaction_id, previous_balance, new_balance, change_source, db_user
                  FROM balance_audit
                 WHERE wallet_id = ?
                 ORDER BY id
                """;
        List<AuditRow> rows = new ArrayList<>();
        try (PreparedStatement ps = audit.prepareStatement(sql)) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long transactionId = rs.getLong(2);
                    Long nullableTransactionId = rs.wasNull() ? null : transactionId;
                    rows.add(new AuditRow(rs.getLong(1), nullableTransactionId, rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return rows;
    }
}
