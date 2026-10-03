package com.example.shortlink.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Locale;

/** Confirms mapping inserts and translates only MySQL short-code primary-key collisions. */
@Component
public class MySqlShortLinkWriter {
    private final ShortLinkMapper mapper;

    public MySqlShortLinkWriter(ShortLinkMapper mapper) {
        this.mapper = mapper;
    }

    public void insertConfirmed(ShortLinkEntity entity) {
        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException(
                        "MySQL did not confirm inserting the short-link mapping.");
            }
        } catch (DuplicateKeyException exception) {
            if (isShortCodePrimaryKeyCollision(exception)) {
                throw new ShortCodeCollisionException(exception);
            }
            throw exception;
        }
    }

    private boolean isShortCodePrimaryKeyCollision(DuplicateKeyException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && isPrimaryKeyDuplicate(sqlException)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrimaryKeyDuplicate(SQLException exception) {
        for (SQLException current = exception;
                current != null;
                current = current.getNextException()) {
            if (current.getErrorCode() == 1062 && namesPrimaryKey(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private boolean namesPrimaryKey(String message) {
        if (message == null) {
            return false;
        }

        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        int keyNameStart = lowerCaseMessage.lastIndexOf("for key ");
        if (keyNameStart < 0) {
            return false;
        }

        String keyName =
                lowerCaseMessage
                        .substring(keyNameStart + "for key ".length())
                        .replace("'", "")
                        .replace("`", "")
                        .replace("\"", "")
                        .trim();
        return keyName.equals("primary") || keyName.endsWith(".primary");
    }
}
