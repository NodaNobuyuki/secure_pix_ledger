package dev.carlosnobuyuki.bank.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * Many requests racing for one idempotency key (ADR-0008). Every request gets its own thread and
 * they are released together, so most of them miss the fast-path lookup and lose at the unique
 * index instead.
 */
@IntegrationTest
class ParallelIdempotencyTests {

  @Autowired TransferService transfers;
  @Autowired DepositService deposits;
  @Autowired AccountService accounts;
  @Autowired JdbcTemplate jdbc;

  @Test
  void sameRequestInParallelExecutesOnce() throws Exception {
    UUID a = funded(1_000);
    UUID b = accounts.open().id();
    TransferCommand command = new TransferCommand(UUID.randomUUID(), a, b, Money.brl(250));

    // Same accounts: the row locks queue the requests, and each one behind the winner inserts the
    // key only after the winner committed, so it hits the unique violation and replays.
    List<Attempt> attempts = runAtOnce(Collections.nCopies(50, command));

    assertThat(attempts).extracting(Attempt::error).containsOnlyNulls();
    List<TransferOutcome> outcomes = attempts.stream().map(Attempt::outcome).toList();
    assertThat(outcomes).filteredOn(o -> !o.replayed()).hasSize(1);
    assertThat(outcomes.stream().map(o -> o.transfer().id()).distinct()).hasSize(1);
    UUID transferId = outcomes.getFirst().transfer().id();
    assertThat(outcomes.getFirst().transfer().status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(transfersWithKey(command.idempotencyKey())).isEqualTo(1);
    assertThat(entriesOf(transferId)).isEqualTo(2);
    assertThat(balance(a)).isEqualTo(750);
    assertThat(balance(b)).isEqualTo(250);
  }

  @Test
  void differentRequestsRacingForOneKeyLetExactlyOneWin() throws Exception {
    UUID key = UUID.randomUUID();
    // Disjoint accounts: the requests share no row lock and collide only on the key's index.
    List<TransferCommand> commands =
        IntStream.range(0, 20)
            .mapToObj(
                i -> new TransferCommand(key, funded(1_000), accounts.open().id(), Money.brl(100)))
            .toList();

    List<Attempt> attempts = runAtOnce(commands);

    List<Integer> winners =
        IntStream.range(0, attempts.size())
            .filter(i -> attempts.get(i).outcome() != null)
            .boxed()
            .toList();
    assertThat(winners).hasSize(1);
    int winner = winners.getFirst();
    assertThat(attempts.get(winner).outcome().replayed()).isFalse();
    assertThat(attempts)
        .filteredOn(at -> at.error() != null)
        .hasSize(19)
        .allSatisfy(at -> assertThat(at.error()).isInstanceOf(IdempotencyKeyReusedException.class));
    assertThat(transfersWithKey(key)).isEqualTo(1);
    for (int i = 0; i < commands.size(); i++) {
      boolean won = i == winner;
      assertThat(balance(commands.get(i).sourceAccountId())).isEqualTo(won ? 900 : 1_000);
      assertThat(balance(commands.get(i).destinationAccountId())).isEqualTo(won ? 100 : 0);
    }
  }

  private record Attempt(TransferOutcome outcome, Throwable error) {}

  /** Runs each command on its own thread, all released at once; results keep the input order. */
  private List<Attempt> runAtOnce(List<TransferCommand> commands) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<TransferOutcome>> pending = new ArrayList<>();
    List<Attempt> attempts = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(commands.size())) {
      for (TransferCommand command : commands) {
        pending.add(
            pool.submit(
                () -> {
                  start.await();
                  return transfers.transfer(command);
                }));
      }
      start.countDown();
      for (Future<TransferOutcome> future : pending) {
        try {
          attempts.add(new Attempt(future.get(30, TimeUnit.SECONDS), null));
        } catch (ExecutionException e) {
          attempts.add(new Attempt(null, e.getCause()));
        }
      }
    }
    return attempts;
  }

  private UUID funded(long amountMinor) {
    UUID id = accounts.open().id();
    deposits.deposit(UUID.randomUUID(), id, Money.brl(amountMinor));
    return id;
  }

  private long balance(UUID account) {
    return accounts.find(account).orElseThrow().balance().amountMinor();
  }

  private long entriesOf(UUID transferId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, transferId);
  }

  private long transfersWithKey(UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transfers WHERE idempotency_key = ?", Long.class, key);
  }
}
