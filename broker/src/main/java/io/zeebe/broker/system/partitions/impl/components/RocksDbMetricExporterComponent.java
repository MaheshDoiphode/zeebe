package io.zeebe.broker.system.partitions.impl.components;

import static io.zeebe.engine.state.DefaultZeebeDbFactory.DEFAULT_DB_METRIC_EXPORTER_FACTORY;

import io.zeebe.broker.system.partitions.Component;
import io.zeebe.broker.system.partitions.ZeebePartitionState;
import io.zeebe.util.sched.ScheduledTimer;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.time.Duration;

public class RocksDbMetricExporterComponent implements Component<ScheduledTimer> {

  @Override
  public ActorFuture<ScheduledTimer> open(final ZeebePartitionState state) {
    final var metricExporter =
        DEFAULT_DB_METRIC_EXPORTER_FACTORY.apply(
            Integer.toString(state.getPartitionId()), state.getZeebeDb());
    final var metricsTimer =
        state
            .getActor()
            .runAtFixedRate(
                Duration.ofSeconds(5),
                () -> {
                  if (state.getZeebeDb() != null) {
                    metricExporter.exportMetrics();
                  }
                });

    return CompletableActorFuture.completed(metricsTimer);
  }

  @Override
  public ActorFuture<Void> close(final ZeebePartitionState state) {
    state.getMetricsTimer().cancel();
    return CompletableActorFuture.completed(null);
  }

  @Override
  public void onOpen(final ZeebePartitionState state, final ScheduledTimer scheduledTimer) {
    state.setMetricsTimer(scheduledTimer);
  }

  @Override
  public String getName() {
    return "RocksDB metric timer";
  }
}
