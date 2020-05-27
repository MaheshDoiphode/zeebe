/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.partitions;

import io.atomix.raft.snapshot.TransientSnapshot;
import io.zeebe.engine.processor.RandomDuration;
import io.zeebe.engine.processor.StreamProcessor;
import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.spi.SnapshotController;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.ActorCondition;
import io.zeebe.util.sched.SchedulingHints;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.time.Duration;
import org.slf4j.Logger;

public final class AsyncSnapshotDirector extends Actor {

  public static final Duration MINIMUM_SNAPSHOT_PERIOD = Duration.ofMinutes(1);

  private static final Logger LOG = Loggers.SNAPSHOT_LOGGER;
  private static final String LOG_MSG_WAIT_UNTIL_COMMITTED =
      "Finished taking snapshot, need to wait until last written event position {} is committed, current commit position is {}. After that snapshot can be marked as valid.";
  private static final String ERROR_MSG_ON_RESOLVE_PROCESSED_POS =
      "Unexpected error in resolving last processed position.";
  private static final String ERROR_MSG_ON_RESOLVE_WRITTEN_POS =
      "Unexpected error in resolving last written position.";
  private static final String ERROR_MSG_MOVE_SNAPSHOT =
      "Unexpected exception occurred on moving valid snapshot.";

  private final SnapshotController snapshotController;
  private final LogStream logStream;
  private final Duration snapshotRate;
  private final String processorName;
  private final StreamProcessor streamProcessor;
  private final String actorName;

  private ActorCondition commitCondition;
  private Long lastWrittenEventPosition;
  private TransientSnapshot pendingSnapshot;
  private long lowerBoundSnapshotPosition;
  private boolean takingSnapshot;

  public AsyncSnapshotDirector(
      final int nodeId,
      final StreamProcessor streamProcessor,
      final SnapshotController snapshotController,
      final LogStream logStream,
      final Duration snapshotRate) {
    this.streamProcessor = streamProcessor;
    this.snapshotController = snapshotController;
    this.logStream = logStream;
    this.processorName = streamProcessor.getName();
    this.snapshotRate = snapshotRate;
    this.actorName = buildActorName(nodeId, "SnapshotDirector-" + logStream.getPartitionId());
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  protected void onActorStarting() {
    actor.setSchedulingHints(SchedulingHints.ioBound());
    final var firstSnapshotTime =
        RandomDuration.getRandomDurationMinuteBased(MINIMUM_SNAPSHOT_PERIOD, snapshotRate);
    actor.runDelayed(firstSnapshotTime, this::scheduleSnapshotOnRate);

    lastWrittenEventPosition = null;
    commitCondition = actor.onCondition(getConditionNameForPosition(), this::onCommitCheck);
    logStream.registerOnCommitPositionUpdatedCondition(commitCondition);
  }

  @Override
  protected void onActorCloseRequested() {
    logStream.removeOnCommitPositionUpdatedCondition(commitCondition);
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (actor.isClosed()) {
      return CompletableActorFuture.completed(null);
    }

    return super.closeAsync();
  }

  private void scheduleSnapshotOnRate() {
    actor.runAtFixedRate(snapshotRate, this::prepareTakingSnapshot);
    prepareTakingSnapshot();
  }

  private String getConditionNameForPosition() {
    return getName() + "-wait-for-endPosition-committed";
  }

  private void prepareTakingSnapshot() {
    if (takingSnapshot) {
      return;
    }

    takingSnapshot = true;
    final var futureLastProcessedPosition = streamProcessor.getLastProcessedPositionAsync();
    actor.runOnCompletion(
        futureLastProcessedPosition,
        (lastProcessedPosition, error) -> {
          if (error == null) {
            if (lastProcessedPosition == StreamProcessor.UNSET_POSITION) {
              LOG.debug(
                  "We will skip taking this snapshot, because we haven't processed something yet.");
              takingSnapshot = false;
              return;
            }

            this.lowerBoundSnapshotPosition = lastProcessedPosition;
            takeSnapshot();
          } else {
            LOG.error(ERROR_MSG_ON_RESOLVE_PROCESSED_POS, error);
            takingSnapshot = false;
          }
        });
  }

  private void takeSnapshot() {
    final var tempSnapshotPosition = lowerBoundSnapshotPosition;

    logStream
        .getCommitPositionAsync()
        .onComplete(
            (commitPosition, errorOnRetrievingCommitPosition) -> {
              if (errorOnRetrievingCommitPosition == null) {
                final var optionalPendingSnapshot =
                    snapshotController.takeTempSnapshot(tempSnapshotPosition);
                if (optionalPendingSnapshot.isEmpty()) {
                  LOG.warn(
                      "Failed to obtain a pending snapshot directory for position {}",
                      tempSnapshotPosition);
                  takingSnapshot = false;
                  return;
                }

                LOG.debug("Created snapshot for {}", processorName);
                pendingSnapshot = optionalPendingSnapshot.get();

                final ActorFuture<Long> lastWrittenPosition =
                    streamProcessor.getLastWrittenPositionAsync();
                actor.runOnCompletion(
                    lastWrittenPosition,
                    (endPosition, error) -> {
                      if (error == null) {
                        LOG.info(LOG_MSG_WAIT_UNTIL_COMMITTED, endPosition, commitPosition);
                        lastWrittenEventPosition = endPosition;
                        onCommitCheck();
                      } else {
                        lastWrittenEventPosition = null;
                        takingSnapshot = false;
                        pendingSnapshot = null;
                        LOG.error(ERROR_MSG_ON_RESOLVE_WRITTEN_POS, error);
                      }
                    });
              } else {
                takingSnapshot = false;
                LOG.error(
                    "Unexpected error on retrieving commit position",
                    errorOnRetrievingCommitPosition);
              }
            });
  }

  private void onCommitCheck() {
    logStream
        .getCommitPositionAsync()
        .onComplete(
            (currentCommitPosition, error) -> {
              if (pendingSnapshot != null
                  && lastWrittenEventPosition != null
                  && currentCommitPosition >= lastWrittenEventPosition) {

                LOG.info(
                    "Current commit position {} is greater then {}, snapshot is valid.",
                    currentCommitPosition,
                    lastWrittenEventPosition);
                try {
                  pendingSnapshot.commit();
//                  snapshotController.commitSnapshot(pendingSnapshot);

                  // todo(zell) should be done via listener
//                  snapshotController.replicateLatestSnapshot(actor::submit);

                } catch (final Exception ex) {
                  LOG.error(ERROR_MSG_MOVE_SNAPSHOT, ex);
                } finally {
                  lastWrittenEventPosition = null;
                  takingSnapshot = false;
                  pendingSnapshot = null;
                }
              }
            });
  }
}
