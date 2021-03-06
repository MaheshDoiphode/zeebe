/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.client.api.response.ActivateJobsResponse;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.test.UpgradeTestCase.TestCaseBuilder;
import io.zeebe.test.util.TestUtil;
import io.zeebe.util.VersionUtil;
import io.zeebe.util.collection.Tuple;
import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.agrona.IoUtil;
import org.awaitility.Awaitility;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class UpgradeTest {

  public static final String PROCESS_ID = "process";
  public static final String CHILD_PROCESS_ID = "childProc";
  private static final String CURRENT_VERSION = "current-test";
  private static final String TASK = "task";
  private static final String JOB = TASK;
  private static final String MESSAGE = "message";
  private static final File SHARED_DATA;

  private static final String LAST_VERSION = VersionUtil.getPreviousVersion();

  static {
    final var sharedDataPath =
        Optional.ofNullable(System.getenv("ZEEBE_CI_SHARED_DATA"))
            .map(Paths::get)
            .orElse(Paths.get(System.getProperty("tmpdir", "/tmp"), "shared"));
    SHARED_DATA = sharedDataPath.toAbsolutePath().toFile();
    IoUtil.ensureDirectoryExists(SHARED_DATA, "temporary folder for Docker");
  }

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder(SHARED_DATA);
  @Rule public ContainerStateRule state = new ContainerStateRule();

  @Rule
  public RuleChain chain =
      RuleChain.outerRule(new Timeout(5, TimeUnit.MINUTES)).around(tmpFolder).around(state);

  @Parameter public String name;

  @Parameter(1)
  public UpgradeTestCase testCase;

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {
            "job",
            scenario()
                .deployWorkflow(jobWorkflow())
                .createInstance()
                .beforeUpgrade(UpgradeTest::activateJob)
                .afterUpgrade(UpgradeTest::completeJob)
                .done()
          },
          {
            "message subscription",
            scenario()
                .deployWorkflow(messageWorkflow())
                .createInstance(Map.of("key", "123"))
                .beforeUpgrade(UpgradeTest::awaitOpenMessageSubscription)
                .afterUpgrade(UpgradeTest::publishMessage)
                .done()
          },
          {
            "message start event",
            scenario()
                .deployWorkflow(msgStartWorkflow())
                .beforeUpgrade(UpgradeTest::awaitStartMessageSubscription)
                .afterUpgrade(UpgradeTest::publishMessage)
                .done()
          },
          // TODO (saig0): enable the test case when upgrading from 0.25.0
          // - an upgrade from 0.24.0 is not possible because a bug fix (#4959) causes an issue in
          // the reprocessing (#5268)
          //            {
          //            "message event sub-process",
          //            scenario()
          //                .deployWorkflow(
          //                    Bpmn.createExecutableProcess(PROCESS_ID)
          //                        .eventSubProcess(
          //                            "event-subprocess",
          //                            eventSubProcess ->
          //                                eventSubProcess
          //                                    .startEvent()
          //                                    .message(
          //                                        m ->
          // m.name(MESSAGE).zeebeCorrelationKeyExpression("key"))
          //                                    .interrupting(false)
          //                                    .endEvent())
          //                        .startEvent()
          //                        .serviceTask(TASK, t -> t.zeebeJobType(TASK))
          //                        .endEvent()
          //                        .done())
          //                .createInstance(Map.of("key", "123"))
          //                .beforeUpgrade(
          //                    state -> {
          //                      publishMessage(state, -1L, -1L);
          //
          //                      TestUtil.waitUntil(
          //                          () -> state.hasElementInState("event-subprocess",
          // "ELEMENT_COMPLETED"));
          //
          //                      return activateJob(state);
          //                    })
          //                .afterUpgrade(UpgradeTest::completeJob)
          //                .done()
          //          },
          {
            "timer",
            scenario()
                .deployWorkflow(timerWorkflow())
                .beforeUpgrade(UpgradeTest::awaitTimerCreation)
                .afterUpgrade(UpgradeTest::timerTriggered)
                .done()
          },
          {
            "incident",
            scenario()
                .deployWorkflow(incidentWorkflow())
                .createInstance()
                .beforeUpgrade(UpgradeTest::awaitIncidentCreation)
                .afterUpgrade(UpgradeTest::resolveIncident)
                .done()
          },
          {
            "publish message",
            scenario()
                .deployWorkflow(messageWorkflow())
                .beforeUpgrade(
                    state -> {
                      publishMessage(state, -1L, -1L);
                      return -1L;
                    })
                .afterUpgrade(
                    (state, l1, l2) -> {
                      state
                          .client()
                          .newCreateInstanceCommand()
                          .bpmnProcessId(PROCESS_ID)
                          .latestVersion()
                          .variables(Map.of("key", "123"))
                          .send();
                      TestUtil.waitUntil(() -> state.hasLogContaining(MESSAGE, "CORRELATED"));
                    })
                .done()
          },
          {
            "call activity",
            scenario()
                .deployWorkflow(
                    new Tuple<>(parentWorkflow(), PROCESS_ID),
                    new Tuple<>(childWorkflow(), CHILD_PROCESS_ID))
                .createInstance()
                .afterUpgrade(
                    (state, wfKey, key) -> {
                      TestUtil.waitUntil(() -> state.hasElementInState(JOB, "CREATED"));

                      final var jobsResponse =
                          state
                              .client()
                              .newActivateJobsCommand()
                              .jobType(TASK)
                              .maxJobsToActivate(1)
                              .send()
                              .join();
                      assertThat(jobsResponse.getJobs()).hasSize(1);

                      TestUtil.waitUntil(() -> state.hasElementInState(JOB, "ACTIVATED"));

                      state
                          .client()
                          .newCompleteCommand(jobsResponse.getJobs().get(0).getKey())
                          .send()
                          .join();
                      TestUtil.waitUntil(
                          () -> state.hasLogContaining(CHILD_PROCESS_ID, "COMPLETED"));
                    })
                .done()
          },
          {
            "parallel gateway",
            scenario()
                .deployWorkflow(
                    Bpmn.createExecutableProcess(PROCESS_ID)
                        .startEvent()
                        .parallelGateway("fork")
                        .serviceTask(TASK, t -> t.zeebeJobType(TASK))
                        .parallelGateway("join")
                        .moveToNode("fork")
                        .sequenceFlowId("to-join")
                        .connectTo("join")
                        .endEvent()
                        .done())
                .createInstance()
                .beforeUpgrade(UpgradeTest::activateJob)
                .afterUpgrade(UpgradeTest::completeJob)
                .done()
          },
          {
            "exclusive gateway",
            scenario()
                .deployWorkflow(
                    Bpmn.createExecutableProcess(PROCESS_ID)
                        .startEvent()
                        .exclusiveGateway()
                        .sequenceFlowId("s1")
                        .conditionExpression("x > 5")
                        .serviceTask(TASK, t -> t.zeebeJobType(TASK))
                        .endEvent()
                        .moveToLastExclusiveGateway()
                        .sequenceFlowId("s2")
                        .defaultFlow()
                        .serviceTask("other-task", t -> t.zeebeJobType("other-task"))
                        .endEvent()
                        .done())
                .createInstance(Map.of("x", 10))
                .beforeUpgrade(UpgradeTest::activateJob)
                .afterUpgrade(UpgradeTest::completeJob)
                .done()
          }
        });
  }

  @Ignore
  @Test
  public void oldGatewayWithNewBroker() {
    // given
    state
        .broker(CURRENT_VERSION, tmpFolder.getRoot().getPath())
        .withStandaloneGateway(LAST_VERSION)
        .start(true);
    final long wfInstanceKey = testCase.setUp(state.client());

    // when
    final long key = testCase.runBefore(state);

    // then
    testCase.runAfter(state, wfInstanceKey, key);
    TestUtil.waitUntil(() -> state.hasElementInState(PROCESS_ID, "ELEMENT_COMPLETED"));
  }

  @Ignore("https://github.com/zeebe-io/zeebe/issues/5385")
  @Test
  public void upgradeWithSnapshot() {
    upgradeZeebe(true);
  }

  @Test
  public void upgradeWithoutSnapshot() {
    upgradeZeebe(false);
  }

  private void upgradeZeebe(final boolean withSnapshot) {
    // given
    state.broker(LAST_VERSION, tmpFolder.getRoot().getPath()).start(true);
    final long wfInstanceKey = testCase.setUp(state.client());
    final long key = testCase.runBefore(state);

    // when
    final File snapshot = new File(tmpFolder.getRoot(), "raft-partition/partitions/1/snapshots/");

    if (withSnapshot) {

      state.close();

      state.broker(LAST_VERSION, tmpFolder.getRoot().getPath()).start(false);

      // since 0.24, no snapshot is created when the broker is closed
      Awaitility.await()
          .atMost(Duration.ofMinutes(2))
          .untilAsserted(
              () ->
                  assertThat(snapshot)
                      .describedAs("Expected that a snapshot is created")
                      .exists()
                      .isNotEmptyDirectory());

      state.close();

    } else {
      // since 0.24, no snapshot is created when the broker is closed
      state.close();

      assertThat(snapshot)
          .describedAs("Expected that no snapshot is created")
          .exists()
          .isEmptyDirectory();
    }

    // then
    state.broker(CURRENT_VERSION, tmpFolder.getRoot().getPath()).start(true);
    testCase.runAfter(state, wfInstanceKey, key);

    TestUtil.waitUntil(() -> state.hasElementInState(PROCESS_ID, "ELEMENT_COMPLETED"));
  }

  private static BpmnModelInstance jobWorkflow() {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .serviceTask(TASK, t -> t.zeebeJobType(TASK))
        .endEvent()
        .done();
  }

  private static long activateJob(final ContainerStateRule state) {
    TestUtil.waitUntil(() -> state.hasElementInState(JOB, "CREATED"));

    final ActivateJobsResponse jobsResponse =
        state.client().newActivateJobsCommand().jobType(TASK).maxJobsToActivate(1).send().join();
    assertThat(jobsResponse.getJobs()).hasSize(1);

    TestUtil.waitUntil(() -> state.hasElementInState(JOB, "ACTIVATED"));
    return jobsResponse.getJobs().get(0).getKey();
  }

  private static void completeJob(
      final ContainerStateRule state, final long wfInstanceKey, final long key) {
    state.client().newCompleteCommand(key).send().join();
  }

  private static BpmnModelInstance messageWorkflow() {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .intermediateCatchEvent(
            "catch", b -> b.message(m -> m.name(MESSAGE).zeebeCorrelationKeyExpression("key")))
        .endEvent()
        .done();
  }

  private static long awaitOpenMessageSubscription(final ContainerStateRule state) {
    TestUtil.waitUntil(() -> state.hasLogContaining("MESSAGE_SUBSCRIPTION", "OPENED"));
    return -1L;
  }

  private static void publishMessage(
      final ContainerStateRule state, final long wfInstanceKey, final long key) {
    state
        .client()
        .newPublishMessageCommand()
        .messageName(MESSAGE)
        .correlationKey("123")
        .timeToLive(Duration.ofMinutes(5))
        .variables(Map.of("x", 1))
        .send()
        .join();

    TestUtil.waitUntil(() -> state.hasMessageInState(MESSAGE, "PUBLISHED"));
  }

  private static BpmnModelInstance msgStartWorkflow() {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .message(b -> b.zeebeCorrelationKeyExpression("key").name(MESSAGE))
        .endEvent()
        .done();
  }

  private static long awaitStartMessageSubscription(final ContainerStateRule state) {
    TestUtil.waitUntil(() -> state.hasLogContaining("MESSAGE_START_EVENT_SUBSCRIPTION", "OPENED"));
    return -1L;
  }

  private static BpmnModelInstance timerWorkflow() {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .timerWithCycle("R/PT1S")
        .endEvent()
        .done();
  }

  private static long awaitTimerCreation(final ContainerStateRule state) {
    TestUtil.waitUntil(() -> state.hasLogContaining("TIMER", "CREATED"));
    return -1L;
  }

  private static void timerTriggered(
      final ContainerStateRule state, final long wfInstanceKey, final long key) {
    TestUtil.waitUntil(() -> state.hasLogContaining("TIMER", "TRIGGERED"));
  }

  private static BpmnModelInstance incidentWorkflow() {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .serviceTask("failingTask", t -> t.zeebeJobType(TASK).zeebeInputExpression("foo", "foo"))
        .done();
  }

  private static long awaitIncidentCreation(final ContainerStateRule state) {
    TestUtil.waitUntil(() -> state.hasLogContaining("INCIDENT", "CREATED"));
    return state.getIncidentKey();
  }

  private static void resolveIncident(
      final ContainerStateRule state, final long wfInstanceKey, final long key) {
    state
        .client()
        .newSetVariablesCommand(wfInstanceKey)
        .variables(Map.of("foo", "bar"))
        .send()
        .join();

    state.client().newResolveIncidentCommand(key).send().join();
    final ActivateJobsResponse job =
        state.client().newActivateJobsCommand().jobType(TASK).maxJobsToActivate(1).send().join();
    state.client().newCompleteCommand(job.getJobs().get(0).getKey()).send().join();
  }

  private static BpmnModelInstance parentWorkflow() {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .callActivity("c", b -> b.zeebeProcessId(CHILD_PROCESS_ID))
        .endEvent()
        .done();
  }

  private static BpmnModelInstance childWorkflow() {
    return Bpmn.createExecutableProcess(CHILD_PROCESS_ID)
        .startEvent()
        .serviceTask(TASK, b -> b.zeebeJobType(TASK))
        .endEvent()
        .done();
  }

  private static TestCaseBuilder scenario() {
    return UpgradeTestCase.builder();
  }
}
