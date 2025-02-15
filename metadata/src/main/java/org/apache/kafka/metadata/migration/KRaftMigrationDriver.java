/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.metadata.migration;

import org.apache.kafka.common.metadata.ConfigRecord;
import org.apache.kafka.common.metadata.MetadataRecordType;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.image.MetadataDelta;
import org.apache.kafka.image.MetadataImage;
import org.apache.kafka.image.MetadataProvenance;
import org.apache.kafka.image.loader.LoaderManifest;
import org.apache.kafka.image.loader.LoaderManifestType;
import org.apache.kafka.image.publisher.MetadataPublisher;
import org.apache.kafka.metadata.BrokerRegistration;
import org.apache.kafka.queue.EventQueue;
import org.apache.kafka.queue.KafkaEventQueue;
import org.apache.kafka.raft.LeaderAndEpoch;
import org.apache.kafka.raft.OffsetAndEpoch;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.fault.FaultHandler;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This class orchestrates and manages the state related to a ZK to KRaft migration. An event thread is used to
 * serialize events coming from various threads and listeners.
 */
public class KRaftMigrationDriver implements MetadataPublisher {
    private final static Consumer<Throwable> NO_OP_HANDLER = ex -> { };

    private final Time time;
    private final Logger log;
    private final int nodeId;
    private final MigrationClient zkMigrationClient;
    private final LegacyPropagator propagator;
    private final ZkRecordConsumer zkRecordConsumer;
    private final KafkaEventQueue eventQueue;
    private final FaultHandler faultHandler;
    /**
     * A callback for when the migration state has been recovered from ZK. This is used to delay the installation of this
     * MetadataPublisher with MetadataLoader.
     */
    private final Consumer<MetadataPublisher> initialZkLoadHandler;
    private volatile LeaderAndEpoch leaderAndEpoch;
    private volatile MigrationDriverState migrationState;
    private volatile ZkMigrationLeadershipState migrationLeadershipState;
    private volatile MetadataImage image;

    public KRaftMigrationDriver(
        int nodeId,
        ZkRecordConsumer zkRecordConsumer,
        MigrationClient zkMigrationClient,
        LegacyPropagator propagator,
        Consumer<MetadataPublisher> initialZkLoadHandler,
        FaultHandler faultHandler
    ) {
        this.nodeId = nodeId;
        this.zkRecordConsumer = zkRecordConsumer;
        this.zkMigrationClient = zkMigrationClient;
        this.propagator = propagator;
        this.time = Time.SYSTEM;
        LogContext logContext = new LogContext("[KRaftMigrationDriver id=" + nodeId + "] ");
        this.log = logContext.logger(KRaftMigrationDriver.class);
        this.migrationState = MigrationDriverState.UNINITIALIZED;
        this.migrationLeadershipState = ZkMigrationLeadershipState.EMPTY;
        this.eventQueue = new KafkaEventQueue(Time.SYSTEM, logContext, "controller-" + nodeId + "-migration-driver-");
        this.image = MetadataImage.EMPTY;
        this.leaderAndEpoch = LeaderAndEpoch.UNKNOWN;
        this.initialZkLoadHandler = initialZkLoadHandler;
        this.faultHandler = faultHandler;
    }

    public void start() {
        eventQueue.prepend(new PollEvent());
    }

    public void shutdown() throws InterruptedException {
        eventQueue.beginShutdown("KRaftMigrationDriver#shutdown");
        log.debug("Shutting down KRaftMigrationDriver");
        eventQueue.close();
    }

    // Visible for testing
    CompletableFuture<MigrationDriverState> migrationState() {
        CompletableFuture<MigrationDriverState> stateFuture = new CompletableFuture<>();
        eventQueue.append(() -> stateFuture.complete(migrationState));
        return stateFuture;
    }

    private void initializeMigrationState() {
        log.info("Recovering migration state");
        apply("Recovery", zkMigrationClient::getOrCreateMigrationRecoveryState);
        String maybeDone = migrationLeadershipState.zkMigrationComplete() ? "done" : "not done";
        log.info("Recovered migration state {}. ZK migration is {}.", migrationLeadershipState, maybeDone);
        initialZkLoadHandler.accept(this);
        // Let's transition to INACTIVE state and wait for leadership events.
        transitionTo(MigrationDriverState.INACTIVE);
    }

    private boolean isControllerQuorumReadyForMigration() {
        // TODO implement this
        return true;
    }

    private boolean imageDoesNotContainAllBrokers(MetadataImage image, Set<Integer> brokerIds) {
        for (BrokerRegistration broker : image.cluster().brokers().values()) {
            if (broker.isMigratingZkBroker()) {
                brokerIds.remove(broker.id());
            }
        }
        return !brokerIds.isEmpty();
    }

    private boolean areZkBrokersReadyForMigration() {
        if (image == MetadataImage.EMPTY) {
            // TODO maybe add WAIT_FOR_INITIAL_METADATA_PUBLISH state to avoid this kind of check?
            log.info("Waiting for initial metadata publish before checking if Zk brokers are registered.");
            return false;
        }

        // First check the brokers registered in ZK
        Set<Integer> zkBrokerRegistrations = zkMigrationClient.readBrokerIds();
        if (imageDoesNotContainAllBrokers(image, zkBrokerRegistrations)) {
            log.info("Still waiting for ZK brokers {} to register with KRaft.", zkBrokerRegistrations);
            return false;
        }

        // Once all of those are found, check the topic assignments. This is much more expensive than listing /brokers
        Set<Integer> zkBrokersWithAssignments = zkMigrationClient.readBrokerIdsFromTopicAssignments();
        if (imageDoesNotContainAllBrokers(image, zkBrokersWithAssignments)) {
            log.info("Still waiting for ZK brokers {} to register with KRaft.", zkBrokersWithAssignments);
            return false;
        }

        return true;
    }

    /**
     * Apply a function which transforms our internal migration state.
     *
     * @param name  A descriptive name of the function that is being applied
     * @param stateMutator  A function which performs some migration operations and possibly transforms our internal state
     */
    private void apply(String name, Function<ZkMigrationLeadershipState, ZkMigrationLeadershipState> stateMutator) {
        ZkMigrationLeadershipState beforeState = this.migrationLeadershipState;
        ZkMigrationLeadershipState afterState = stateMutator.apply(beforeState);
        log.trace("{} transitioned from {} to {}", name, beforeState, afterState);
        this.migrationLeadershipState = afterState;
    }

    private boolean isValidStateChange(MigrationDriverState newState) {
        if (migrationState == newState)
            return true;
        switch (migrationState) {
            case UNINITIALIZED:
            case DUAL_WRITE:
                return newState == MigrationDriverState.INACTIVE;
            case INACTIVE:
                return newState == MigrationDriverState.WAIT_FOR_CONTROLLER_QUORUM;
            case WAIT_FOR_CONTROLLER_QUORUM:
                return
                    newState == MigrationDriverState.INACTIVE ||
                    newState == MigrationDriverState.WAIT_FOR_BROKERS;
            case WAIT_FOR_BROKERS:
                return
                    newState == MigrationDriverState.INACTIVE ||
                    newState == MigrationDriverState.BECOME_CONTROLLER;
            case BECOME_CONTROLLER:
                return
                    newState == MigrationDriverState.INACTIVE ||
                    newState == MigrationDriverState.ZK_MIGRATION ||
                    newState == MigrationDriverState.KRAFT_CONTROLLER_TO_BROKER_COMM;
            case ZK_MIGRATION:
                return
                    newState == MigrationDriverState.INACTIVE ||
                    newState == MigrationDriverState.KRAFT_CONTROLLER_TO_BROKER_COMM;
            case KRAFT_CONTROLLER_TO_BROKER_COMM:
                return
                    newState == MigrationDriverState.INACTIVE ||
                    newState == MigrationDriverState.DUAL_WRITE;
            default:
                log.error("Migration driver trying to transition from an unknown state {}", migrationState);
                return false;
        }
    }

    private void transitionTo(MigrationDriverState newState) {
        if (!isValidStateChange(newState)) {
            log.error("Error transition in migration driver from {} to {}", migrationState, newState);
            return;
        }
        if (newState != migrationState) {
            log.debug("{} transitioning from {} to {} state", nodeId, migrationState, newState);
        } else {
            log.trace("{} transitioning from {} to {} state", nodeId, migrationState, newState);
        }
        switch (newState) {
            case UNINITIALIZED:
                // No state can transition to UNITIALIZED.
                throw new IllegalStateException("Illegal transition from " + migrationState + " to " + newState + " " +
                "state in Zk to KRaft migration");
            case INACTIVE:
                // Any state can go to INACTIVE.
                break;
        }
        migrationState = newState;
    }

    @Override
    public String name() {
        return "KRaftMigrationDriver";
    }

    @Override
    public void onControllerChange(LeaderAndEpoch newLeaderAndEpoch) {
        eventQueue.append(new KRaftLeaderEvent(newLeaderAndEpoch));
    }

    @Override
    public void onMetadataUpdate(
        MetadataDelta delta,
        MetadataImage newImage,
        LoaderManifest manifest
    ) {
        enqueueMetadataChangeEvent(delta,
            newImage,
            manifest.provenance(),
            manifest.type() == LoaderManifestType.SNAPSHOT,
            NO_OP_HANDLER);
    }

    /**
     * Construct and enqueue a {@link MetadataChangeEvent} with a given completion handler. In production use cases,
     * this handler is a no-op. This method exists so we can add additional logic in our unit tests to wait for the
     * enqueued event to finish executing.
     */
    void enqueueMetadataChangeEvent(
        MetadataDelta delta,
        MetadataImage newImage,
        MetadataProvenance provenance,
        boolean isSnapshot,
        Consumer<Throwable> completionHandler
    ) {
        MetadataChangeEvent metadataChangeEvent = new MetadataChangeEvent(
            delta,
            newImage,
            provenance,
            isSnapshot,
            completionHandler
        );
        eventQueue.append(metadataChangeEvent);
    }

    @Override
    public void close() throws Exception {
        eventQueue.close();
    }

    // Events handled by Migration Driver.
    abstract class MigrationEvent implements EventQueue.Event {
        @SuppressWarnings("ThrowableNotThrown")
        @Override
        public void handleException(Throwable e) {
            if (e instanceof MigrationClientAuthException) {
                KRaftMigrationDriver.this.faultHandler.handleFault("Encountered ZooKeeper authentication in " + this, e);
            } else if (e instanceof MigrationClientException) {
                log.info(String.format("Encountered ZooKeeper error during event %s. Will retry.", this), e.getCause());
            } else if (e instanceof RejectedExecutionException) {
                log.debug("Not processing {} because the event queue is closed.", this);
            } else {
                KRaftMigrationDriver.this.faultHandler.handleFault("Unhandled error in " + this, e);
            }
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName();
        }
    }

    class PollEvent extends MigrationEvent {
        @Override
        public void run() throws Exception {
            switch (migrationState) {
                case UNINITIALIZED:
                    initializeMigrationState();
                    break;
                case INACTIVE:
                    // Nothing to do when the driver is inactive. We need to wait on the
                    // controller node's state to move forward.
                    break;
                case WAIT_FOR_CONTROLLER_QUORUM:
                    eventQueue.append(new WaitForControllerQuorumEvent());
                    break;
                case BECOME_CONTROLLER:
                    eventQueue.append(new BecomeZkControllerEvent());
                    break;
                case WAIT_FOR_BROKERS:
                    eventQueue.append(new WaitForZkBrokersEvent());
                    break;
                case ZK_MIGRATION:
                    eventQueue.append(new MigrateMetadataEvent());
                    break;
                case KRAFT_CONTROLLER_TO_BROKER_COMM:
                    eventQueue.append(new SendRPCsToBrokersEvent());
                    break;
                case DUAL_WRITE:
                    // Nothing to do in the PollEvent. If there's metadata change, we use
                    // MetadataChange event to drive the writes to Zookeeper.
                    break;
            }

            // Poll again after some time
            long deadline = time.nanoseconds() + NANOSECONDS.convert(1, SECONDS);
            eventQueue.scheduleDeferred(
                "poll",
                new EventQueue.DeadlineFunction(deadline),
                new PollEvent());
        }
    }

    class KRaftLeaderEvent extends MigrationEvent {
        private final LeaderAndEpoch leaderAndEpoch;

        KRaftLeaderEvent(LeaderAndEpoch leaderAndEpoch) {
            this.leaderAndEpoch = leaderAndEpoch;
        }

        @Override
        public void run() throws Exception {
            // We can either be the active controller or just resigned from being the controller.
            KRaftMigrationDriver.this.leaderAndEpoch = leaderAndEpoch;
            boolean isActive = leaderAndEpoch.isLeader(KRaftMigrationDriver.this.nodeId);
            switch (migrationState) {
                case UNINITIALIZED:
                    // Poll and retry after initialization
                    long deadline = time.nanoseconds() + NANOSECONDS.convert(10, SECONDS);
                    eventQueue.scheduleDeferred(
                        "poll",
                        new EventQueue.DeadlineFunction(deadline),
                        this);
                    break;
                default:
                    if (!isActive) {
                        apply("KRaftLeaderEvent is not active", state -> ZkMigrationLeadershipState.EMPTY);
                        transitionTo(MigrationDriverState.INACTIVE);
                    } else {
                        // Apply the new KRaft state
                        apply("KRaftLeaderEvent is active", state -> state.withNewKRaftController(nodeId, leaderAndEpoch.epoch()));
                        // Before becoming the controller fo ZkBrokers, we need to make sure the
                        // Controller Quorum can handle migration.
                        transitionTo(MigrationDriverState.WAIT_FOR_CONTROLLER_QUORUM);
                    }
                    break;
            }
        }
    }

    class WaitForControllerQuorumEvent extends MigrationEvent {

        @Override
        public void run() throws Exception {
            switch (migrationState) {
                case WAIT_FOR_CONTROLLER_QUORUM:
                    if (isControllerQuorumReadyForMigration()) {
                        log.debug("Controller Quorum is ready for Zk to KRaft migration");
                        // Note that leadership would not change here. Hence we do not need to
                        // `apply` any leadership state change.
                        transitionTo(MigrationDriverState.WAIT_FOR_BROKERS);
                    }
                    break;
                default:
                    // Ignore the event as we're not trying to become controller anymore.
                    break;
            }
        }
    }

    class BecomeZkControllerEvent extends MigrationEvent {
        @Override
        public void run() throws Exception {
            if (migrationState == MigrationDriverState.BECOME_CONTROLLER) {
                apply("BecomeZkLeaderEvent", zkMigrationClient::claimControllerLeadership);
                if (migrationLeadershipState.zkControllerEpochZkVersion() == -1) {
                    log.debug("Unable to claim leadership, will retry until we learn of a different KRaft leader");
                } else {
                    if (!migrationLeadershipState.zkMigrationComplete()) {
                        transitionTo(MigrationDriverState.ZK_MIGRATION);
                    } else {
                        transitionTo(MigrationDriverState.KRAFT_CONTROLLER_TO_BROKER_COMM);
                    }
                }
            }
        }
    }

    class WaitForZkBrokersEvent extends MigrationEvent {
        @Override
        public void run() throws Exception {
            switch (migrationState) {
                case WAIT_FOR_BROKERS:
                    if (areZkBrokersReadyForMigration()) {
                        log.debug("Zk brokers are registered and ready for migration");
                        transitionTo(MigrationDriverState.BECOME_CONTROLLER);
                    }
                    break;
                default:
                    // Ignore the event as we're not in the appropriate state anymore.
                    break;
            }
        }
    }

    class MigrateMetadataEvent extends MigrationEvent {
        @Override
        public void run() throws Exception {
            Set<Integer> brokersInMetadata = new HashSet<>();
            log.info("Starting ZK migration");
            zkRecordConsumer.beginMigration();
            try {
                AtomicInteger count = new AtomicInteger(0);
                zkMigrationClient.readAllMetadata(batch -> {
                    try {
                        if (log.isTraceEnabled()) {
                            log.trace("Migrating {} records from ZK: {}", batch.size(), recordBatchToString(batch));
                        } else {
                            log.info("Migrating {} records from ZK", batch.size());
                        }
                        CompletableFuture<?> future = zkRecordConsumer.acceptBatch(batch);
                        count.addAndGet(batch.size());
                        future.get();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e.getCause());
                    }
                }, brokersInMetadata::add);
                OffsetAndEpoch offsetAndEpochAfterMigration = zkRecordConsumer.completeMigration();
                log.info("Completed migration of metadata from Zookeeper to KRaft. A total of {} metadata records were " +
                         "generated. The current metadata offset is now {} with an epoch of {}. Saw {} brokers in the " +
                         "migrated metadata {}.",
                    count.get(),
                    offsetAndEpochAfterMigration.offset(),
                    offsetAndEpochAfterMigration.epoch(),
                    brokersInMetadata.size(),
                    brokersInMetadata);
                ZkMigrationLeadershipState newState = migrationLeadershipState.withKRaftMetadataOffsetAndEpoch(
                    offsetAndEpochAfterMigration.offset(),
                    offsetAndEpochAfterMigration.epoch());
                apply("Migrate metadata from Zk", state -> zkMigrationClient.setMigrationRecoveryState(newState));
                transitionTo(MigrationDriverState.KRAFT_CONTROLLER_TO_BROKER_COMM);
            } catch (Throwable t) {
                zkRecordConsumer.abortMigration();
                super.handleException(t);
            }
        }
    }

    class SendRPCsToBrokersEvent extends MigrationEvent {

        @Override
        public void run() throws Exception {
            // Ignore sending RPCs to the brokers since we're no longer in the state.
            if (migrationState == MigrationDriverState.KRAFT_CONTROLLER_TO_BROKER_COMM) {
                if (image.highestOffsetAndEpoch().compareTo(migrationLeadershipState.offsetAndEpoch()) >= 0) {
                    log.trace("Sending RPCs to broker before moving to dual-write mode using " +
                        "at offset and epoch {}", image.highestOffsetAndEpoch());
                    propagator.sendRPCsToBrokersFromMetadataImage(image, migrationLeadershipState.zkControllerEpoch());
                    // Migration leadership state doesn't change since we're not doing any Zk writes.
                    transitionTo(MigrationDriverState.DUAL_WRITE);
                } else {
                    log.trace("Ignoring using metadata image since migration leadership state is at a greater offset and epoch {}",
                        migrationLeadershipState.offsetAndEpoch());
                }
            }
        }
    }

    class MetadataChangeEvent extends MigrationEvent {
        private final MetadataDelta delta;
        private final MetadataImage image;
        private final MetadataProvenance provenance;
        private final boolean isSnapshot;
        private final Consumer<Throwable> completionHandler;

        MetadataChangeEvent(
            MetadataDelta delta,
            MetadataImage image,
            MetadataProvenance provenance,
            boolean isSnapshot,
            Consumer<Throwable> completionHandler
        ) {
            this.delta = delta;
            this.image = image;
            this.provenance = provenance;
            this.isSnapshot = isSnapshot;
            this.completionHandler = completionHandler;
        }

        @Override
        public void run() throws Exception {
            KRaftMigrationDriver.this.image = image;
            String metadataType = isSnapshot ? "snapshot" : "delta";

            if (migrationState != MigrationDriverState.DUAL_WRITE) {
                log.trace("Received metadata {}, but the controller is not in dual-write " +
                    "mode. Ignoring the change to be replicated to Zookeeper", metadataType);
                completionHandler.accept(null);
                return;
            }
            if (delta.featuresDelta() != null) {
                propagator.setMetadataVersion(image.features().metadataVersion());
            }

            if (image.highestOffsetAndEpoch().compareTo(migrationLeadershipState.offsetAndEpoch()) >= 0) {
                if (delta.topicsDelta() != null) {
                    delta.topicsDelta().changedTopics().forEach((topicId, topicDelta) -> {
                        if (delta.topicsDelta().createdTopicIds().contains(topicId)) {
                            apply("Create topic " + topicDelta.name(), migrationState ->
                                zkMigrationClient.createTopic(
                                    topicDelta.name(),
                                    topicId,
                                    topicDelta.partitionChanges(),
                                    migrationState));
                        } else {
                            apply("Updating topic " + topicDelta.name(), migrationState ->
                                zkMigrationClient.updateTopicPartitions(
                                    Collections.singletonMap(topicDelta.name(), topicDelta.partitionChanges()),
                                    migrationState));
                        }
                    });
                }

                // For configs and client quotas, we need to send all of the data to the ZK client since we persist
                // everything for a given entity in a single ZK node.
                if (delta.configsDelta() != null) {
                    delta.configsDelta().changes().forEach((configResource, configDelta) ->
                        apply("Updating config resource " + configResource, migrationState ->
                            zkMigrationClient.writeConfigs(configResource, image.configs().configMapForResource(configResource), migrationState)));
                }

                if (delta.clientQuotasDelta() != null) {
                    delta.clientQuotasDelta().changes().forEach((clientQuotaEntity, clientQuotaDelta) -> {
                        Map<String, Double> quotaMap = image.clientQuotas().entities().get(clientQuotaEntity).quotaMap();
                        apply("Updating client quota " + clientQuotaEntity, migrationState ->
                            zkMigrationClient.writeClientQuotas(clientQuotaEntity.entries(), quotaMap, migrationState));
                    });
                }

                if (delta.producerIdsDelta() != null) {
                    apply("Updating next producer ID", migrationState ->
                        zkMigrationClient.writeProducerId(delta.producerIdsDelta().nextProducerId(), migrationState));
                }

                // TODO: Unhappy path: Probably relinquish leadership and let new controller
                //  retry the write?
                if (delta.topicsDelta() != null || delta.clusterDelta() != null) {
                    log.trace("Sending RPCs to brokers for metadata {}.", metadataType);
                    propagator.sendRPCsToBrokersFromMetadataDelta(delta, image,
                            migrationLeadershipState.zkControllerEpoch());
                } else {
                    log.trace("Not sending RPCs to brokers for metadata {} since no relevant metadata has changed", metadataType);
                }
            } else {
                log.info("Ignoring {} {} which contains metadata that has already been written to ZK.", metadataType, provenance);
            }
            completionHandler.accept(null);
        }

        @Override
        public void handleException(Throwable e) {
            completionHandler.accept(e);
            super.handleException(e);
        }
    }

    static String recordBatchToString(Collection<ApiMessageAndVersion> batch) {
        String batchString = batch.stream().map(apiMessageAndVersion -> {
            if (apiMessageAndVersion.message().apiKey() == MetadataRecordType.CONFIG_RECORD.id()) {
                StringBuilder sb = new StringBuilder();
                sb.append("ApiMessageAndVersion(");
                ConfigRecord record = (ConfigRecord) apiMessageAndVersion.message();
                sb.append("ConfigRecord(");
                sb.append("resourceType=");
                sb.append(record.resourceType());
                sb.append(", resourceName=");
                sb.append(record.resourceName());
                sb.append(", name=");
                sb.append(record.name());
                sb.append(")");
                sb.append(" at version ");
                sb.append(apiMessageAndVersion.version());
                sb.append(")");
                return sb.toString();
            } else {
                return apiMessageAndVersion.toString();
            }
        }).collect(Collectors.joining(","));
        return "[" + batchString + "]";
    }
}
