package com.example.app.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConstraintViolationCodesTest {

    private static final ConstraintErrorCodes ACCOUNTS = () -> Map.of("uq_accounts_email", ErrorCode.USER_EMAIL_TAKEN);

    private static DataIntegrityViolationException violation(String constraint, String sqlState) {
        ConstraintViolationException cause = new ConstraintViolationException("violated", new SQLException("violated", sqlState), constraint);
        return new DataIntegrityViolationException("violated", cause);
    }

    @Test
    @DisplayName("A declared constraint answers its module's code; another unique violation is DUPLICATE_RECORD; anything else none")
    void mapsDeclaredThenUniqueThenNothing() {
        ConstraintViolationCodes codes = new ConstraintViolationCodes(List.of(ACCOUNTS));

        assertThat(codes.codeFor(violation("uq_accounts_email", "23505"))).contains(ErrorCode.USER_EMAIL_TAKEN);
        assertThat(codes.codeFor(violation("uq_other", "23505"))).contains(ErrorCode.DUPLICATE_RECORD);
        assertThat(codes.codeFor(violation("ck_other", "23514"))).isEmpty();
    }

    @Test
    @DisplayName("A foreign key answers by the statement that tripped it: an insert or update its write code, a delete its in-use code")
    void foreignKeyAnswersByTheStatementThatTrippedIt() {
        // Kit codes only: bootstrap copies this test into projects without the example module.
        ConstraintErrorCodes lists = new ConstraintErrorCodes() {
            @Override
            public Map<String, ErrorCode> byConstraintName() {
                return Map.of("fk_items_list_id", ErrorCode.REQUEST_INVALID);
            }

            @Override
            public Map<String, ErrorCode> byForeignKeyOnWrite() {
                return Map.of("fk_items_list_id", ErrorCode.RESOURCE_NOT_FOUND);
            }
        };
        ConstraintViolationCodes codes = new ConstraintViolationCodes(List.of(lists));

        assertThat(codes.codeFor(foreignKeyViolation("insert into items (id, list_id) values (?,?)"))).contains(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(codes.codeFor(foreignKeyViolation("update items set list_id=? where id=?"))).contains(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(codes.codeFor(foreignKeyViolation("delete from lists where id=? and version=?"))).contains(ErrorCode.REQUEST_INVALID);
        assertThat(new ConstraintViolationCodes(List.of(ACCOUNTS)).codeFor(foreignKeyViolation("insert into items values (?)"))).isEmpty();
    }

    private static DataIntegrityViolationException foreignKeyViolation(String statement) {
        ConstraintViolationException cause = new ConstraintViolationException("violated", new SQLException("violated", "23503"), statement, "fk_items_list_id");
        return new DataIntegrityViolationException("violated", cause);
    }

    @Test
    @DisplayName("Two modules claiming one constraint stop startup")
    void refusesAConstraintClaimedTwice() {
        ConstraintErrorCodes other = () -> Map.of("uq_accounts_email", ErrorCode.DUPLICATE_RECORD);

        assertThatThrownBy(() -> new ConstraintViolationCodes(List.of(ACCOUNTS, other)))
                .hasMessageContaining("uq_accounts_email");
    }
}
