package com.financial.transactions.challenge.service.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionFiltersTest {

    @Nested
    @DisplayName("Page validation")
    class PageValidation {

        @Test
        @DisplayName("GIVEN page = 0, WHEN filters are created, THEN page is accepted as 0")
        void acceptsPageZero() {
            // given
            int page = 0;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, page, 20);

            // then
            assertThat(filters.page()).isEqualTo(0);
        }

        @Test
        @DisplayName("GIVEN page = 5, WHEN filters are created, THEN page is accepted as 5")
        void acceptsPositivePage() {
            // given
            int page = 5;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, page, 20);

            // then
            assertThat(filters.page()).isEqualTo(5);
        }

        @Test
        @DisplayName("GIVEN page = -1, WHEN filters are created, THEN IllegalArgumentException is thrown")
        void rejectsNegativePage() {
            // given
            int page = -1;

            // when
            // then
            assertThatThrownBy(() -> new TransactionFilters(null, null, null, page, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Limit defaults and clamping")
    class LimitDefaultsAndClamping {

        @Test
        @DisplayName("GIVEN limit = 0, WHEN filters are created, THEN limit defaults to 20")
        void defaultsZeroLimitToTwenty() {
            // given
            int limit = 0;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, 0, limit);

            // then
            assertThat(filters.limit()).isEqualTo(20);
        }

        @Test
        @DisplayName("GIVEN limit = -5, WHEN filters are created, THEN limit defaults to 20")
        void defaultsNegativeLimitToTwenty() {
            // given
            int limit = -5;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, 0, limit);

            // then
            assertThat(filters.limit()).isEqualTo(20);
        }

        @Test
        @DisplayName("GIVEN limit = 50, WHEN filters are created, THEN limit stays 50")
        void keepsLimitWithinRange() {
            // given
            int limit = 50;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, 0, limit);

            // then
            assertThat(filters.limit()).isEqualTo(50);
        }

        @Test
        @DisplayName("GIVEN limit = 100, WHEN filters are created, THEN limit stays exactly 100")
        void keepsLimitAtUpperBoundInclusive() {
            // given
            int limit = 100;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, 0, limit);

            // then
            assertThat(filters.limit()).isEqualTo(100);
        }

        @Test
        @DisplayName("GIVEN limit = 101, WHEN filters are created, THEN limit is clamped to 100")
        void clampsLimitJustAboveMax() {
            // given
            int limit = 101;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, 0, limit);

            // then
            assertThat(filters.limit()).isEqualTo(100);
        }

        @Test
        @DisplayName("GIVEN limit = 10000, WHEN filters are created, THEN limit is clamped to 100")
        void clampsLimitFarAboveMax() {
            // given
            int limit = 10000;

            // when
            TransactionFilters filters = new TransactionFilters(null, null, null, 0, limit);

            // then
            assertThat(filters.limit()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("of factory method")
    class OfFactoryMethod {

        @Test
        @DisplayName("GIVEN page = null and limit = null, WHEN TransactionFilters.of is called, THEN page defaults to 0 and limit defaults to 20")
        void defaultsNullPageAndLimit() {
            // given
            Integer page = null;
            Integer limit = null;

            // when
            TransactionFilters filters = TransactionFilters.of(null, null, null, page, limit);

            // then
            assertThat(filters.page()).isEqualTo(0);
            assertThat(filters.limit()).isEqualTo(20);
        }

        @Test
        @DisplayName("GIVEN page = 3 and limit = 50, WHEN TransactionFilters.of is called, THEN both values are preserved exactly")
        void preservesNonNullPageAndLimit() {
            // given
            Integer page = 3;
            Integer limit = 50;

            // when
            TransactionFilters filters = TransactionFilters.of(null, null, null, page, limit);

            // then
            assertThat(filters.page()).isEqualTo(3);
            assertThat(filters.limit()).isEqualTo(50);
        }

        @Test
        @DisplayName("GIVEN accountId, status and type all null, WHEN TransactionFilters.of is called, THEN no exception is thrown and all three remain null")
        void allowsAllCriteriaNull() {
            // given
            // when
            TransactionFilters filters = TransactionFilters.of(null, null, null, null, null);

            // then
            assertThat(filters.accountId()).isNull();
            assertThat(filters.status()).isNull();
            assertThat(filters.type()).isNull();
        }

        @Test
        @DisplayName("GIVEN limit = 200 passed to TransactionFilters.of, WHEN filters are created, THEN limit is clamped to 100")
        void clampsLimitThroughFactory() {
            // given
            Integer limit = 200;

            // when
            TransactionFilters filters = TransactionFilters.of(null, null, null, null, limit);

            // then
            assertThat(filters.limit()).isEqualTo(100);
        }
    }
}
