package dev.carlosnobuyuki.bank.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.RejectionReason;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fires 500 transfers at once against PostgreSQL to prove the locking strategy (ADR-0007): no
 * overdraft under contention, no deadlock on crossed pairs, and money is conserved. Each workload
 * is built so its outcome is the same under every interleaving.
 */
@IntegrationTest
class ConcurrentTransfersTests {

  // More threads than the Hikari pool (10), so requests also queue for connections.
  private static final int THREADS = 50;
  private static final int ACCOUNTS = 10;
  private static final long INITIAL_BALANCE = 10_000;

  @Autowired TransferService transfers;
  @Autowired DepositService deposits;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;

  @Test
  void keepsBooksConsistentUnderContention() throws Exception {
    List<UUID> ids = IntStream.range(0, ACCOUNTS).mapToObj(i -> funded()).toList();
    UUID hot = ids.get(0);
    UUID a = ids.get(1);
    UUID b = ids.get(2);
    List<UUID> mesh = ids.subList(3, ACCOUNTS);
    // Fixed seed: a failing run can be reproduced with the same workload.
    Random random = new Random(42);

    // 100 x 500 against a balance of 10_000: exactly 20 fit, whichever ones win the locks.
    List<TransferCommand> hotDrain = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      hotDrain.add(command(hot, pick(mesh, random), 500));
    }
    // Locking in request order would deadlock A->B against B->A; locking by id must not. Neither
    // side can run dry: even if all its debits run first, it ends at exactly zero.
    List<TransferCommand> crossed = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      crossed.add(command(a, b, 100));
      crossed.add(command(b, a, 100));
    }
    List<TransferCommand> meshTraffic = new ArrayList<>();
    while (meshTraffic.size() < 200) {
      UUID source = pick(mesh, random);
      UUID destination = pick(mesh, random);
      if (!source.equals(destination)) {
        meshTraffic.add(command(source, destination, 1 + random.nextInt(5_000)));
      }
    }
    List<TransferCommand> all = new ArrayList<>(hotDrain);
    all.addAll(crossed);
    all.addAll(meshTraffic);
    Collections.shuffle(all, random);

    Map<UUID, Transfer> results = runAtOnce(all);

    assertThat(statuses(hotDrain, results, TransferStatus.COMPLETED)).isEqualTo(20);
    assertThat(statuses(hotDrain, results, TransferStatus.REJECTED)).isEqualTo(80);
    assertThat(balance(hot)).isZero();

    assertThat(statuses(crossed, results, TransferStatus.COMPLETED)).isEqualTo(crossed.size());
    assertThat(balance(a)).isEqualTo(INITIAL_BALANCE);
    assertThat(balance(b)).isEqualTo(INITIAL_BALANCE);

    assertThat(results.values())
        .filteredOn(t -> t.status() == TransferStatus.REJECTED)
        .allSatisfy(
            t -> assertThat(t.rejectionReason()).contains(RejectionReason.INSUFFICIENT_FUNDS));
    assertThat(ids).allSatisfy(id -> assertThat(balance(id)).isNotNegative());
    assertThat(ids.stream().mapToLong(this::balance).sum())
        .as("money held by the test accounts")
        .isEqualTo(ACCOUNTS * INITIAL_BALANCE);

    long completed = statuses(all, results, TransferStatus.COMPLETED);
    // One credit entry per funding deposit, two per completed transfer, none per rejection.
    assertThat(ids.stream().mapToLong(this::entriesOn).sum()).isEqualTo(ACCOUNTS + 2 * completed);
  }

  /** Releases every task at the same moment and collects results keyed by idempotency key. */
  private Map<UUID, Transfer> runAtOnce(List<TransferCommand> commands) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Map<UUID, Future<TransferOutcome>> pending = new HashMap<>();
    Map<UUID, Transfer> results = new HashMap<>();
    List<Throwable> failures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
      for (TransferCommand command : commands) {
        pending.put(
            command.idempotencyKey(),
            pool.submit(
                () -> {
                  start.await();
                  return transfers.transfer(command);
                }));
      }
      start.countDown();
      for (Map.Entry<UUID, Future<TransferOutcome>> entry : pending.entrySet()) {
        try {
          results.put(entry.getKey(), entry.getValue().get(60, TimeUnit.SECONDS).transfer());
        } catch (ExecutionException e) {
          failures.add(e.getCause());
        }
      }
    }
    assertThat(failures).as("transfers that threw (deadlock, lock timeout, ...)").isEmpty();
    return results;
  }

  private static long statuses(
      List<TransferCommand> commands, Map<UUID, Transfer> results, TransferStatus status) {
    return commands.stream()
        .filter(c -> results.get(c.idempotencyKey()).status() == status)
        .count();
  }

  private static TransferCommand command(UUID source, UUID destination, long amountMinor) {
    return new TransferCommand(UUID.randomUUID(), source, destination, Money.brl(amountMinor));
  }

  private static UUID pick(List<UUID> ids, Random random) {
    return ids.get(random.nextInt(ids.size()));
  }

  private UUID funded() {
    UUID id = accounts.open().id();
    deposits.deposit(UUID.randomUUID(), id, Money.brl(INITIAL_BALANCE));
    return id;
  }

  private long balance(UUID account) {
    return accounts.find(account).orElseThrow().balance().amountMinor();
  }

  private long entriesOn(UUID account) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, account);
  }
}
