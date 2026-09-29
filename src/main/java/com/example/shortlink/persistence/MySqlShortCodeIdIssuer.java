package com.example.shortlink.persistence;

import com.example.shortlink.service.ShortCodeIdIssuer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Statement;

@Component
public class MySqlShortCodeIdIssuer implements ShortCodeIdIssuer {

    private final JdbcTemplate jdbcTemplate;

    public MySqlShortCodeIdIssuer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long issue() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int insertedRows = jdbcTemplate.update(
                connection -> connection.prepareStatement(
                        "INSERT INTO short_code_issuance () VALUES ()",
                        Statement.RETURN_GENERATED_KEYS),
                keyHolder);
        Number generatedId = keyHolder.getKey();
        if (insertedRows != 1 || generatedId == null) {
            throw new IllegalStateException("MySQL did not return an issued short-code ID.");
        }
        return generatedId.longValue();
    }
}
