package com.walletapi.wallet.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.walletapi.wallet.domain.OperationType;
import com.walletapi.wallet.domain.PageResult;
import com.walletapi.wallet.domain.StatementQuery;
import com.walletapi.wallet.domain.Wallet;
import com.walletapi.wallet.domain.WalletTransaction;

@Repository
public class WalletQueryRepository {

    private static final String SELECT_WALLET = """
            SELECT id, holder_name, holder_document, balance, created_at, updated_at
              FROM v_wallet
             WHERE id = :id
            """;

    private static final String EXISTS_WALLET = """
            SELECT COUNT(*) FROM v_wallet WHERE id = :id
            """;

    private static final String SELECT_TRANSACTION = """
            SELECT id, wallet_id, idempotency_key, operation_type, amount, balance_after, description, created_at
              FROM v_wallet_transaction
             WHERE wallet_id = :walletId
               AND id = :id
            """;

    private static final String COUNT_STATEMENT = "SELECT COUNT(*) FROM v_wallet_transaction";

    private static final String SELECT_STATEMENT = """
            SELECT id, wallet_id, idempotency_key, operation_type, amount, balance_after, description, created_at
              FROM v_wallet_transaction
            """;

    private static final String STATEMENT_PAGE = """
             ORDER BY id DESC
            OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
            """;

    private static final RowMapper<Wallet> WALLET_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getLong("id"),
            rs.getString("holder_name"),
            rs.getString("holder_document"),
            money(rs, "balance"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<WalletTransaction> TRANSACTION_MAPPER = (rs, rowNum) -> new WalletTransaction(
            rs.getLong("id"),
            rs.getLong("wallet_id"),
            rs.getString("idempotency_key"),
            OperationType.valueOf(rs.getString("operation_type")),
            money(rs, "amount"),
            money(rs, "balance_after"),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public WalletQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Wallet> findWallet(long id) {
        return jdbc.query(SELECT_WALLET, new MapSqlParameterSource("id", id), WALLET_MAPPER)
                .stream()
                .findFirst();
    }

    public boolean walletExists(long id) {
        Long count = jdbc.queryForObject(EXISTS_WALLET, new MapSqlParameterSource("id", id), Long.class);
        return count != null && count > 0;
    }

    public Optional<WalletTransaction> findTransaction(long walletId, long transactionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("walletId", walletId)
                .addValue("id", transactionId);
        return jdbc.query(SELECT_TRANSACTION, params, TRANSACTION_MAPPER)
                .stream()
                .findFirst();
    }

    public PageResult<WalletTransaction> findStatement(StatementQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("walletId", query.walletId())
                .addValue("offset", query.offset())
                .addValue("size", query.size());

        StringBuilder where = new StringBuilder(" WHERE wallet_id = :walletId");
        if (query.from() != null) {
            where.append(" AND created_at >= :fromDate");
            params.addValue("fromDate", query.from(), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (query.to() != null) {
            where.append(" AND created_at < :toDate");
            params.addValue("toDate", query.to(), Types.TIMESTAMP_WITH_TIMEZONE);
        }

        Long total = jdbc.queryForObject(COUNT_STATEMENT + where, params, Long.class);
        List<WalletTransaction> content = jdbc.query(SELECT_STATEMENT + where + STATEMENT_PAGE, params, TRANSACTION_MAPPER);
        return new PageResult<>(content, query.page(), query.size(), total == null ? 0 : total);
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).setScale(2, RoundingMode.UNNECESSARY);
    }
}
