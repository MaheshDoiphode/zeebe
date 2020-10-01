package io.zeebe.broker.system.partitions.impl.components;

import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.storage.journal.JournalReader.Mode;
import io.zeebe.broker.system.partitions.Component;
import io.zeebe.broker.system.partitions.ZeebePartitionState;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;

public class RaftLogReaderComponent implements Component<RaftLogReader> {

  @Override
  public ActorFuture<RaftLogReader> open(final ZeebePartitionState state) {
    final var reader = state.getRaftPartition().getServer().openReader(-1, Mode.COMMITS);
    return CompletableActorFuture.completed(reader);
  }

  @Override
  public ActorFuture<Void> close(final ZeebePartitionState state) {
    state.getRaftLogReader().close();
    return CompletableActorFuture.completed(null);
  }

  @Override
  public void onOpen(final ZeebePartitionState state, final RaftLogReader reader) {
    state.setRaftLogReader(reader);
  }

  @Override
  public String getName() {
    return "RaftLogReader";
  }
}
