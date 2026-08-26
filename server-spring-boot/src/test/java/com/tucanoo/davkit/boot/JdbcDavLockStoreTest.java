package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.lock.DavLock;
import com.tucanoo.davkit.lock.JdbcDavLockStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Same contract as {@code InMemoryDavLockStoreTest}, on a real table (H2). */
class JdbcDavLockStoreTest {

    private final Instant t0 = Instant.parse("2026-08-23T10:00:00Z");
    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:locks-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
    }

    private JdbcDavLockStore storeAt(Instant now) {
        return new JdbcDavLockStore(dataSource, "davkit_locks", Clock.fixed(now, ZoneOffset.UTC)).ensureSchema();
    }

    private DavLock lock(String token, String key, Instant expires) {
        return new DavLock(token, key, "dave", "DAVE-PC\\dave", expires, Duration.ofMinutes(5));
    }

    @Test
    void secondLockOnSameKeyIsRejectedUntilExpiry() {
        var store = storeAt(t0);
        assertThat(store.tryCreate(lock("a", "letter:1", t0.plusSeconds(60)))).isPresent();
        assertThat(store.tryCreate(lock("b", "letter:1", t0.plusSeconds(60)))).isEmpty();
        assertThat(store.find("letter:1").orElseThrow().token()).isEqualTo("a");

        var later = storeAt(t0.plusSeconds(61)); // same table, later clock: "a" is stale
        assertThat(later.find("letter:1")).isEmpty();
        assertThat(later.tryCreate(lock("b", "letter:1", t0.plusSeconds(120)))).isPresent();
        assertThat(later.findByToken("a")).isEmpty();
    }

    @Test
    void refreshExtendsAndRemoveClears() {
        var store = storeAt(t0);
        store.tryCreate(lock("a", "letter:1", t0.plusSeconds(60)));
        DavLock refreshed = store.refresh("a", Duration.ofSeconds(600)).orElseThrow();
        assertThat(refreshed.expiresAt()).isEqualTo(t0.plusSeconds(600));
        assertThat(refreshed.timeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(store.find("letter:1")).contains(refreshed);
        assertThat(store.refresh("nope", Duration.ofSeconds(1))).isEmpty();
        assertThat(store.remove("a")).isTrue();
        assertThat(store.remove("a")).isFalse();
        assertThat(store.find("letter:1")).isEmpty();
    }

    @Test
    void sweepRemovesOnlyExpiredRows() {
        var store = storeAt(t0);
        store.tryCreate(lock("a", "letter:1", t0.plusSeconds(10)));
        store.tryCreate(lock("b", "letter:2", t0.plusSeconds(1000)));
        assertThat(storeAt(t0.plusSeconds(11)).sweepExpired()).isEqualTo(1);
        assertThat(store.findByToken("b")).isPresent();
    }

    @Test
    void ensureSchemaIsIdempotentAndTableNameIsValidated() {
        storeAt(t0);
        storeAt(t0);
        assertThatThrownBy(() -> new JdbcDavLockStore(dataSource, "locks; drop table x", Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
