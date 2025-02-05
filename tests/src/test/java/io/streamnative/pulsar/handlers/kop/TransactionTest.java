/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop;

import static org.apache.kafka.clients.CommonClientConfigs.CLIENT_ID_CONFIG;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import com.google.common.collect.ImmutableMap;
import io.streamnative.pulsar.handlers.kop.coordinator.transaction.TransactionCoordinator;
import io.streamnative.pulsar.handlers.kop.coordinator.transaction.TransactionState;
import io.streamnative.pulsar.handlers.kop.coordinator.transaction.TransactionStateManager;
import io.streamnative.pulsar.handlers.kop.scala.Either;
import io.streamnative.pulsar.handlers.kop.storage.PartitionLog;
import io.streamnative.pulsar.handlers.kop.storage.ProducerStateManagerSnapshot;
import io.streamnative.pulsar.handlers.kop.storage.TxnMetadata;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.clients.admin.AbortTransactionSpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeProducersResult;
import org.apache.kafka.clients.admin.ListTransactionsOptions;
import org.apache.kafka.clients.admin.ListTransactionsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.ProducerState;
import org.apache.kafka.clients.admin.TransactionDescription;
import org.apache.kafka.clients.admin.TransactionListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.message.FetchResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.pulsar.common.naming.TopicName;
import org.awaitility.Awaitility;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Transaction test.
 */
@Slf4j
public class TransactionTest extends KopProtocolHandlerTestBase {

    private static final int TRANSACTION_TIMEOUT_CONFIG_VALUE = 600 * 1000;

    protected void setupTransactions() {
        this.conf.setDefaultNumberOfNamespaceBundles(4);
        this.conf.setKafkaMetadataNamespace("__kafka");
        this.conf.setOffsetsTopicNumPartitions(10);
        this.conf.setKafkaTxnLogTopicNumPartitions(10);
        this.conf.setKafkaTxnProducerStateTopicNumPartitions(10);
        this.conf.setKafkaTransactionCoordinatorEnabled(true);
        this.conf.setBrokerDeduplicationEnabled(true);

        // disable automatic snapshots and purgeTx
        this.conf.setKafkaTxnPurgeAbortedTxnIntervalSeconds(0);
        this.conf.setKafkaTxnProducerStateTopicSnapshotIntervalSeconds(0);

        // enable tx expiration, but producers have
        // a very long TRANSACTION_TIMEOUT_CONFIG
        // so they won't expire by default
        this.conf.setKafkaTransactionalIdExpirationMs(5000);
        this.conf.setKafkaTransactionalIdExpirationEnable(true);
    }

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        setupTransactions();
        super.internalSetup();
        log.info("success internal setup");
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }


    @AfterMethod(alwaysRun = true)
    void removeUselessTopics() throws Exception {
        List<String> partitionedTopicList = admin.topics()
                .getPartitionedTopicList(tenant + "/" + namespace);
        for (String topic : partitionedTopicList) {
            log.info("delete partitioned topic {}", topic);
            admin.topics().deletePartitionedTopic(topic, true);
        }

        List<String> topics = admin.topics().getList(tenant + "/" + namespace);
        for (String topic : topics) {
            log.info("delete non-partitioned topic {}", topic);
            admin.topics().delete(topic,  true, true);
        }
    }


    @DataProvider(name = "produceConfigProvider")
    protected static Object[][] produceConfigProvider() {
        // isBatch
        return new Object[][]{
                {true},
                {false}
        };
    }

    @Test(timeOut = 1000 * 10, dataProvider = "produceConfigProvider")
    public void readCommittedTest(boolean isBatch) throws Exception {
        basicProduceAndConsumeTest("read-committed-test", "txn-11", "read_committed", isBatch);
    }

    @Test(timeOut = 1000 * 10, dataProvider = "produceConfigProvider")
    public void readUncommittedTest(boolean isBatch) throws Exception {
        basicProduceAndConsumeTest("read-uncommitted-test", "txn-12", "read_uncommitted", isBatch);
    }

    @Test(timeOut = 1000 * 10)
    public void testInitTransaction() {
        String transactionalId = "myProducer_" + UUID.randomUUID();
        final KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();
        producer.close();
    }

    @Test(timeOut = 1000 * 10)
    public void testMultiCommits() throws Exception {
        final String topic = "test-multi-commits";
        final KafkaProducer<Integer, String> producer1 = buildTransactionProducer("X1");
        final KafkaProducer<Integer, String> producer2 = buildTransactionProducer("X2");
        producer1.initTransactions();
        producer2.initTransactions();
        producer1.beginTransaction();
        producer2.beginTransaction();
        producer1.send(new ProducerRecord<>(topic, "msg-0")).get();
        producer2.send(new ProducerRecord<>(topic, "msg-1")).get();
        producer1.commitTransaction();
        producer2.commitTransaction();
        producer1.close();
        producer2.close();

        final TransactionStateManager stateManager = getProtocolHandler()
                .getTransactionCoordinator(conf.getKafkaTenant())
                .getTxnManager();
        final Function<String, TransactionState> getTransactionState = transactionalId ->
                Optional.ofNullable(stateManager.getTransactionState(transactionalId).getRight())
                        .map(optEpochAndMetadata -> optEpochAndMetadata.map(epochAndMetadata ->
                                epochAndMetadata.getTransactionMetadata().getState()).orElse(TransactionState.EMPTY))
                        .orElse(TransactionState.EMPTY);
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertEquals(getTransactionState.apply("X1"), TransactionState.COMPLETE_COMMIT);
                    assertEquals(getTransactionState.apply("X2"), TransactionState.COMPLETE_COMMIT);
                });
    }

    private void basicProduceAndConsumeTest(String topicName,
                                           String transactionalId,
                                           String isolation,
                                           boolean isBatch) throws Exception {

        topicName = topicName + "_" + isBatch + "_" + isolation;

        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        int totalTxnCount = 10;
        int messageCountPerTxn = 10;

        String lastMessage = "";
        for (int txnIndex = 0; txnIndex < totalTxnCount; txnIndex++) {
            producer.beginTransaction();

            String contentBase;
            if (txnIndex % 2 != 0) {
                contentBase = "commit msg txnIndex %s messageIndex %s";
            } else {
                contentBase = "abort msg txnIndex %s messageIndex %s";
            }

            for (int messageIndex = 0; messageIndex < messageCountPerTxn; messageIndex++) {
                String msgContent = String.format(contentBase, txnIndex, messageIndex);
                log.info("send txn message {}", msgContent);
                lastMessage = msgContent;
                if (isBatch) {
                    producer.send(new ProducerRecord<>(topicName, messageIndex, msgContent));
                } else {
                    producer.send(new ProducerRecord<>(topicName, messageIndex, msgContent)).get();
                }
            }
            producer.flush();

            if (txnIndex % 2 != 0) {
                producer.commitTransaction();
            } else {
                producer.abortTransaction();
            }
        }

        final int expected;
        switch (isolation) {
            case "read_committed":
                expected = totalTxnCount * messageCountPerTxn / 2;
                break;
            case "read_uncommitted":
                expected = totalTxnCount * messageCountPerTxn;
                break;
            default:
                expected = -1;
                fail();
        }
        consumeTxnMessage(topicName, expected, lastMessage, isolation);
    }

    private List<String> consumeTxnMessage(String topicName,
                                   int totalMessageCount,
                                   String lastMessage,
                                   String isolation) throws InterruptedException {
        return consumeTxnMessage(topicName,
                totalMessageCount,
                lastMessage,
                isolation,
                "test_consumer");
    }

    private List<String> consumeTxnMessage(String topicName,
                                   int totalMessageCount,
                                   String lastMessage,
                                   String isolation,
                                   String group) throws InterruptedException {
        @Cleanup
        KafkaConsumer<Integer, String> consumer = buildTransactionConsumer(group, isolation);
        consumer.subscribe(Collections.singleton(topicName));

        List<String> messages = new ArrayList<>();

        log.info("waiting for message {} in topic {}", lastMessage, topicName);
        AtomicInteger receiveCount = new AtomicInteger(0);
        while (true) {
            ConsumerRecords<Integer, String> consumerRecords =
                    consumer.poll(Duration.of(100, ChronoUnit.MILLIS));

            boolean readFinish = false;
            for (ConsumerRecord<Integer, String> record : consumerRecords) {
                log.info("Fetch for receive record offset: {}, key: {}, value: {}",
                        record.offset(), record.key(), record.value());
                if (isolation.equals("read_committed")) {
                    assertFalse(record.value().contains("abort"), "in read_committed isolation "
                            + "we read a message that should have been aborted: " + record.value());
                }
                receiveCount.incrementAndGet();
                messages.add(record.value());
                if (lastMessage.equalsIgnoreCase(record.value())) {
                    log.info("received the last message");
                    readFinish = true;
                }
            }

            if (readFinish) {
                log.info("Fetch for read finish.");
                break;
            }
        }
        log.info("Fetch for receive message finish. isolation: {}, receive count: {} messages {}",
                isolation, receiveCount.get(), messages);
        Assert.assertEquals(receiveCount.get(), totalMessageCount, "messages: " + messages);
        log.info("Fetch for finish consume messages. isolation: {}", isolation);

        return messages;
    }

    @Test(timeOut = 1000 * 30)
    public void offsetCommitTest() throws Exception {
        txnOffsetTest("txn-offset-commit-test", 10, true);
    }

    @Test(timeOut = 1000 * 30)
    public void offsetAbortTest() throws Exception {
        txnOffsetTest("txn-offset-abort-test", 10, false);
    }

    public void txnOffsetTest(String topic, int messageCnt, boolean isCommit) throws Exception {
        String groupId = "my-group-id-" + messageCnt + "-" + isCommit;

        List<String> sendMsgs = prepareData(topic, "first send message - ", messageCnt);

        String transactionalId = "myProducer_" + UUID.randomUUID();
        // producer
        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        // consumer
        @Cleanup
        KafkaConsumer<Integer, String> consumer = buildTransactionConsumer(groupId, "read_uncommitted");
        consumer.subscribe(Collections.singleton(topic));

        producer.initTransactions();
        producer.beginTransaction();

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();

        AtomicInteger msgCnt = new AtomicInteger(messageCnt);

        while (msgCnt.get() > 0) {
            ConsumerRecords<Integer, String> records = consumer.poll(Duration.of(1000, ChronoUnit.MILLIS));
            for (ConsumerRecord<Integer, String> record : records) {
                log.info("receive message (first) - {}", record.value());
                Assert.assertEquals(sendMsgs.get(messageCnt - msgCnt.get()), record.value());
                msgCnt.decrementAndGet();
                offsets.put(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1));
            }
        }
        producer.sendOffsetsToTransaction(offsets, groupId);

        if (isCommit) {
            producer.commitTransaction();
            waitForTxnMarkerWriteComplete(offsets, consumer);
        } else {
            producer.abortTransaction();
        }

        resetToLastCommittedPositions(consumer);

        msgCnt = new AtomicInteger(messageCnt);
        while (msgCnt.get() > 0) {
            ConsumerRecords<Integer, String> records = consumer.poll(Duration.of(1000, ChronoUnit.MILLIS));
            if (isCommit) {
                if (records.isEmpty()) {
                    msgCnt.decrementAndGet();
                } else {
                    fail("The transaction was committed, the consumer shouldn't receive any more messages.");
                }
            } else {
                for (ConsumerRecord<Integer, String> record : records) {
                    log.info("receive message (second) - {}", record.value());
                    Assert.assertEquals(sendMsgs.get(messageCnt - msgCnt.get()), record.value());
                    msgCnt.decrementAndGet();
                }
            }
        }
    }

    @DataProvider(name = "basicRecoveryTestAfterTopicUnloadNumTransactions")
    protected static Object[][] basicRecoveryTestAfterTopicUnloadNumTransactions() {
        // isBatch
        return new Object[][]{
                {0},
                {3},
                {5}
        };
    }

    @Test(timeOut = 1000 * 20, dataProvider = "basicRecoveryTestAfterTopicUnloadNumTransactions")
    public void basicRecoveryTestAfterTopicUnload(int numTransactionsBetweenSnapshots) throws Exception {

        String topicName = "basicRecoveryTestAfterTopicUnload_" + numTransactionsBetweenSnapshots;
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        String namespace = TopicName.get(topicName).getNamespace();

        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        int totalTxnCount = 10;
        int messageCountPerTxn = 20;

        String lastMessage = "";
        for (int txnIndex = 0; txnIndex < totalTxnCount; txnIndex++) {
            producer.beginTransaction();

            String contentBase;
            if (txnIndex % 2 != 0) {
                contentBase = "commit msg txnIndex %s messageIndex %s";
            } else {
                contentBase = "abort msg txnIndex %s messageIndex %s";
            }

            for (int messageIndex = 0; messageIndex < messageCountPerTxn; messageIndex++) {
                String msgContent = String.format(contentBase, txnIndex, messageIndex);
                log.info("send txn message {}", msgContent);
                lastMessage = msgContent;
                producer.send(new ProducerRecord<>(topicName, messageIndex, msgContent)).get();
            }
            producer.flush();

            // please note that we always have 1 transactions in state "ONGOING" here
            if (numTransactionsBetweenSnapshots > 0
                    && (txnIndex % numTransactionsBetweenSnapshots) == 0) {
                // force take snapshot
                takeSnapshot(topicName);
            }

            if (txnIndex % 2 != 0) {
                producer.commitTransaction();
            } else {
                producer.abortTransaction();
            }
        }

        waitForTransactionsToBeInStableState(transactionalId);

        // unload the namespace, this will force a recovery
        pulsar.getAdminClient().namespaces().unload(namespace);

        final int expected =  totalTxnCount * messageCountPerTxn / 2;
        consumeTxnMessage(topicName, expected, lastMessage, isolation);
    }


    private TransactionState dumpTransactionState(String transactionalId) {
        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");
        TransactionCoordinator transactionCoordinator =
                protocolHandler.getTransactionCoordinator(tenant);
        Either<Errors, Optional<TransactionStateManager.CoordinatorEpochAndTxnMetadata>> transactionState =
                transactionCoordinator.getTxnManager().getTransactionState(transactionalId);
        log.debug("transactionalId {} status {}", transactionalId, transactionState);
        assertFalse(transactionState.isLeft(), "transaction "
                + transactionalId + " error " + transactionState.getLeft());
        return transactionState.getRight().get().getTransactionMetadata().getState();
    }

    private void waitForTransactionsToBeInStableState(String transactionalId) {
        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");
        TransactionCoordinator transactionCoordinator =
                protocolHandler.getTransactionCoordinator(tenant);
        Awaitility.await().untilAsserted(() -> {
            Either<Errors, Optional<TransactionStateManager.CoordinatorEpochAndTxnMetadata>> transactionState =
                    transactionCoordinator.getTxnManager().getTransactionState(transactionalId);
            log.debug("transactionalId {} status {}", transactionalId, transactionState);
            assertFalse(transactionState.isLeft());
            TransactionState state = transactionState.getRight()
                    .get().getTransactionMetadata().getState();
            boolean isStable;
            switch (state) {
                case COMPLETE_COMMIT:
                case COMPLETE_ABORT:
                case EMPTY:
                    isStable = true;
                    break;
                default:
                    isStable = false;
                    break;
            }
            assertTrue(isStable, "Transaction " + transactionalId
                    + " is not stable to reach a stable state, is it " + state);
        });
    }

    private void takeSnapshot(String topicName) throws Exception {
        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");

        int numPartitions =
                admin.topics().getPartitionedTopicMetadata(topicName).partitions;
        for (int i = 0; i < numPartitions; i++) {
            PartitionLog partitionLog = protocolHandler
                    .getReplicaManager()
                    .getPartitionLog(new TopicPartition(topicName, i), tenant + "/" + namespace);

            // we can only take the snapshot on the only thread that is allowed to process mutations
            // on the state
            partitionLog
                    .takeProducerSnapshot()
                    .get();

        }
    }


    @Test(timeOut = 1000 * 30, dataProvider = "basicRecoveryTestAfterTopicUnloadNumTransactions")
    public void basicTestWithTopicUnload(int numTransactionsBetweenUnloads) throws Exception {

        String topicName = "basicTestWithTopicUnload_" + numTransactionsBetweenUnloads;
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";
        boolean isBatch = false;

        String namespace = TopicName.get(topicName).getNamespace();

        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        int totalTxnCount = 10;
        int messageCountPerTxn = 20;

        String lastMessage = "";
        for (int txnIndex = 0; txnIndex < totalTxnCount; txnIndex++) {
            producer.beginTransaction();

            String contentBase;
            if (txnIndex % 2 != 0) {
                contentBase = "commit msg txnIndex %s messageIndex %s";
            } else {
                contentBase = "abort msg txnIndex %s messageIndex %s";
            }

            for (int messageIndex = 0; messageIndex < messageCountPerTxn; messageIndex++) {
                String msgContent = String.format(contentBase, txnIndex, messageIndex);
                log.info("send txn message {}", msgContent);
                lastMessage = msgContent;
                if (isBatch) {
                    producer.send(new ProducerRecord<>(topicName, messageIndex, msgContent));
                } else {
                    producer.send(new ProducerRecord<>(topicName, messageIndex, msgContent)).get();
                }
            }
            producer.flush();

            if (numTransactionsBetweenUnloads > 0
                    && (txnIndex % numTransactionsBetweenUnloads) == 0) {

                // dump the state before un load, this helps troubleshooting
                // problems in case of flaky test
                TransactionState transactionState = dumpTransactionState(transactionalId);
                assertEquals(TransactionState.ONGOING, transactionState);

                // unload the namespace, this will force a recovery
                pulsar.getAdminClient().namespaces().unload(namespace);
            }

            if (txnIndex % 2 != 0) {
                producer.commitTransaction();
            } else {
                producer.abortTransaction();
            }
        }


        final int expected = totalTxnCount * messageCountPerTxn / 2;
        consumeTxnMessage(topicName, expected, lastMessage, isolation);
    }

    @DataProvider(name = "takeSnapshotBeforeRecovery")
    protected static Object[][] takeSnapshotBeforeRecovery() {
        // isBatch
        return new Object[][]{
                {true},
                {false}
        };
    }

    @Test(timeOut = 1000 * 20, dataProvider = "takeSnapshotBeforeRecovery")
    public void basicRecoveryAbortedTransaction(boolean takeSnapshotBeforeRecovery) throws Exception {

        String topicName = "basicRecoveryAbortedTransaction_" + takeSnapshotBeforeRecovery;
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        String namespace = TopicName.get(topicName).getNamespace();

        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        producer.beginTransaction();

        String firstMessage = "aborted msg 1";

        producer.send(new ProducerRecord<>(topicName, 0, firstMessage)).get();
        producer.flush();

        // force take snapshot
        takeSnapshot(topicName);

        // recovery will re-process the topic from this point onwards
        String secondMessage = "aborted msg 2";
        producer.send(new ProducerRecord<>(topicName, 0, secondMessage)).get();

        producer.abortTransaction();

        producer.beginTransaction();
        String lastMessage = "committed mgs";
        producer.send(new ProducerRecord<>(topicName, 0, "foo")).get();
        producer.send(new ProducerRecord<>(topicName, 0, lastMessage)).get();
        producer.commitTransaction();

        if (takeSnapshotBeforeRecovery) {
            takeSnapshot(topicName);
        }

        waitForTransactionsToBeInStableState(transactionalId);

        // unload the namespace, this will force a recovery
        pulsar.getAdminClient().namespaces().unload(namespace);

        consumeTxnMessage(topicName, 2, lastMessage, isolation);
    }

    @Test(timeOut = 1000 * 30, dataProvider = "takeSnapshotBeforeRecovery")
    public void basicRecoveryAbortedTransactionDueToProducerFenced(boolean takeSnapshotBeforeRecovery)
            throws Exception {

        String topicName = "basicRecoveryAbortedTransactionDueToProducerFenced_" + takeSnapshotBeforeRecovery;
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        String namespace = TopicName.get(topicName).getNamespace();

        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        producer.beginTransaction();

        String firstMessage = "aborted msg 1";

        producer.send(new ProducerRecord<>(topicName, 0, firstMessage)).get();
        producer.flush();
        // force take snapshot
        takeSnapshot(topicName);

        // recovery will re-process the topic from this point onwards
        String secondMessage = "aborted msg 2";
        producer.send(new ProducerRecord<>(topicName, 0, secondMessage)).get();

        log.debug("Starting a second producer");
        KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId);
        producer2.initTransactions();

        // the transaction is automatically aborted, because the first instance of the
        // producer has been fenced
        expectThrows(ProducerFencedException.class, () -> {
            producer.commitTransaction();
        });
        producer.close();

        log.debug("First producer closed");
        producer2.beginTransaction();
        String lastMessage = "committed mgs";
        producer2.send(new ProducerRecord<>(topicName, 0, "foo")).get();
        producer2.send(new ProducerRecord<>(topicName, 0, lastMessage)).get();
        producer2.commitTransaction();
        producer2.close();

        if (takeSnapshotBeforeRecovery) {
            // force take snapshot
            takeSnapshot(topicName);
        }

        waitForTransactionsToBeInStableState(transactionalId);

        // unload the namespace, this will force a recovery
        pulsar.getAdminClient().namespaces().unload(namespace);

        consumeTxnMessage(topicName, 2, lastMessage, isolation);
    }


    @Test(timeOut = 1000 * 20, dataProvider = "takeSnapshotBeforeRecovery")
    public void basicRecoveryAbortedTransactionDueToProducerTimedOut(boolean takeSnapshotBeforeRecovery)
            throws Exception {

        String topicName = "basicRecoveryAbortedTransactionDueToProducerTimedOut_" + takeSnapshotBeforeRecovery;
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        String namespace = TopicName.get(topicName).getNamespace();

        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId, 1000);

        producer.initTransactions();

        producer.beginTransaction();

        String firstMessage = "aborted msg 1";

        producer.send(new ProducerRecord<>(topicName, 0, firstMessage)).get();
        producer.flush();
        // force take snapshot
        takeSnapshot(topicName);

        // recovery will re-process the topic from this point onwards
        String secondMessage = "aborted msg 2";
        producer.send(new ProducerRecord<>(topicName, 0, secondMessage)).get();

        Thread.sleep(conf.getKafkaTransactionalIdExpirationMs() * 2 + 5000);

        // the transaction is automatically aborted, because of producer timeout
        expectThrows(ProducerFencedException.class, () -> {
            producer.commitTransaction();
        });

        producer.close();

        KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId, 1000);
        producer2.initTransactions();
        producer2.beginTransaction();
        String lastMessage = "committed mgs";
        producer2.send(new ProducerRecord<>(topicName, 0, "foo")).get();
        producer2.send(new ProducerRecord<>(topicName, 0, lastMessage)).get();
        producer2.commitTransaction();
        producer2.close();

        if (takeSnapshotBeforeRecovery) {
            // force take snapshot
            takeSnapshot(topicName);
        }

        waitForTransactionsToBeInStableState(transactionalId);

        // unload the namespace, this will force a recovery
        pulsar.getAdminClient().namespaces().unload(namespace);

        consumeTxnMessage(topicName, 2, lastMessage, isolation);
    }


    @Test(timeOut = 1000 * 20)
    public void basicRecoveryAfterDeleteCreateTopic()
            throws Exception {


        String topicName = "basicRecoveryAfterDeleteCreateTopic";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        TopicName fullTopicName = TopicName.get(topicName);

        String namespace = fullTopicName.getNamespace();

        // use Kafka API, this way we assign a topic UUID
        @Cleanup
        AdminClient kafkaAdmin = AdminClient.create(newKafkaAdminClientProperties());
        kafkaAdmin.createTopics(Arrays.asList(new NewTopic(topicName, 4, (short) 1))).all().get();

        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId, 1000);

        producer.initTransactions();

        producer.beginTransaction();

        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.send(new ProducerRecord<>(topicName, 0, "deleted msg 1")).get();
        producer.flush();

        // force take snapshot
        takeSnapshot(topicName);

        String secondMessage = "deleted msg 2";
        producer.send(new ProducerRecord<>(topicName, 0, secondMessage)).get();
        producer.flush();
        producer.close();

        // verify that a non-transactional consumer can read the messages
        consumeTxnMessage(topicName, 10, secondMessage, "read_uncommitted",
                "uncommitted_reader1");

        waitForTransactionsToBeInStableState(transactionalId);

        // delete/create
        pulsar.getAdminClient().namespaces().unload(namespace);
        admin.topics().deletePartitionedTopic(topicName, true);

        // the PH is notified of the deletion using TopicEventListener

        // create the topic again, using the kafka APIs
        kafkaAdmin.createTopics(Arrays.asList(new NewTopic(topicName, 4, (short) 1))).all().get();

        // the snapshot now points to a offset that doesn't make sense in the new topic
        // because the new topic is empty

        KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId, 1000);
        producer2.initTransactions();
        producer2.beginTransaction();
        String lastMessage = "committed mgs";

        // this "send" triggers recovery of the ProducerStateManager on the topic
        producer2.send(new ProducerRecord<>(topicName, 0, "good-message")).get();
        producer2.send(new ProducerRecord<>(topicName, 0, lastMessage)).get();
        producer2.commitTransaction();
        producer2.close();

        consumeTxnMessage(topicName, 2, lastMessage, isolation, "readcommitter-reader-1");
    }

    @Test(timeOut = 10000, dataProvider = "takeSnapshotBeforeRecovery")
    public void testPurgeAbortedTx(boolean takeSnapshotBeforeRecovery) throws Exception {
        String topicName = "testPurgeAbortedTx_" + takeSnapshotBeforeRecovery + "_" + UUID.randomUUID();
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        TopicName fullTopicName = TopicName.get(topicName);

        pulsar.getAdminClient().topics().createPartitionedTopic(topicName, 1);

        String namespace = fullTopicName.getNamespace();
        TopicPartition topicPartition = new TopicPartition(topicName, 0);
        String namespacePrefix = namespace;

        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");

        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "aborted 1")).get(); // OFFSET 0
        producer.flush();
        // this transaction is to be purged later
        producer.abortTransaction();  // OFFSET 1

        waitForTransactionsToBeInStableState(transactionalId);

        PartitionLog partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);
        partitionLog.awaitInitialisation().get();
        assertEquals(0, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

        List<FetchResponseData.AbortedTransaction> abortedIndexList =
                partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
        abortedIndexList.forEach(tx -> {
            log.info("TX {}", tx);
        });
        assertEquals(0, abortedIndexList.get(0).firstOffset());

        producer.beginTransaction();
        String lastMessage = "msg1b";
        producer.send(new ProducerRecord<>(topicName, 0, "msg1")).get();  // OFFSET 2
        producer.send(new ProducerRecord<>(topicName, 0, lastMessage)).get();  // OFFSET 3
        producer.commitTransaction();  // OFFSET 4

        assertEquals(
            consumeTxnMessage(topicName, 2, lastMessage, isolation, "first_group"),
            List.of("msg1", "msg1b"));

       waitForTransactionsToBeInStableState(transactionalId);

        // unload and reload in order to have at least 2 ledgers in the
        // topic, this way we can drop the head ledger
        admin.topics().unload(fullTopicName.getPartition(0).toString());
        admin.lookups().lookupTopic(fullTopicName.getPartition(0).toString());

        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "msg2")).get();  // OFFSET 5
        producer.send(new ProducerRecord<>(topicName, 0, "msg3")).get();  // OFFSET 6
        producer.commitTransaction();  // OFFSET 7

        partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);
        partitionLog.awaitInitialisation().get();
        assertEquals(0L, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

        abortedIndexList =
                partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
        abortedIndexList.forEach(tx -> {
            log.info("TX {}", tx);
        });
        assertEquals(0, abortedIndexList.get(0).firstOffset());
        assertEquals(1, abortedIndexList.size());

        waitForTransactionsToBeInStableState(transactionalId);

        admin.topics().unload(fullTopicName.getPartition(0).toString());
        admin.lookups().lookupTopic(fullTopicName.getPartition(0).toString());
        admin.topics().unload(fullTopicName.getPartition(0).toString());
        admin.lookups().lookupTopic(fullTopicName.getPartition(0).toString());


        if (takeSnapshotBeforeRecovery) {
            takeSnapshot(topicName);
        }

        // validate that the topic has been trimmed
        partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);
        partitionLog.awaitInitialisation().get();
        assertEquals(0L, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

        // all the messages up to here will be trimmed
        log.info("BEFORE TRUNCATE");
        trimConsumedLedgers(fullTopicName.getPartition(0).toString());
        log.info("AFTER TRUNCATE");

        assertSame(partitionLog, protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix));

        assertEquals(7L, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());
        abortedIndexList =
                partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
        abortedIndexList.forEach(tx -> {
            log.info("TX {}", tx);
        });

        assertEquals(1, abortedIndexList.size());
        assertEquals(0, abortedIndexList.get(0).firstOffset());

        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "msg4")).get(); // OFFSET 8
        producer.send(new ProducerRecord<>(topicName, 0, "msg5")).get(); // OFFSET 9
        producer.commitTransaction();  // OFFSET 10

        partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);
        partitionLog.awaitInitialisation().get();
        assertEquals(8L, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

        // this TX is aborted and must not be purged
        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "aborted 2")).get();  // OFFSET 11
        producer.flush();
        producer.abortTransaction();  // OFFSET 12

        waitForTransactionsToBeInStableState(transactionalId);

        abortedIndexList =
                partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
        abortedIndexList.forEach(tx -> {
            log.info("TX {}", tx);
        });

        assertEquals(0, abortedIndexList.get(0).firstOffset());
        assertEquals(11, abortedIndexList.get(1).firstOffset());
        assertEquals(2, abortedIndexList.size());

        producer.beginTransaction();
        String lastMessage2 = "msg6";
        producer.send(new ProducerRecord<>(topicName, 0, lastMessage2)).get();
        producer.commitTransaction();
        producer.close();

        waitForTransactionsToBeInStableState(transactionalId);

        partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);

        partitionLog.awaitInitialisation().get();

        // verify that we have 2 aborted TX in memory
        assertTrue(partitionLog.getProducerStateManager().hasSomeAbortedTransactions());
        abortedIndexList =
                partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
        abortedIndexList.forEach(tx -> {
            log.info("TX {}", tx);
        });

        assertEquals(0, abortedIndexList.get(0).firstOffset());
        assertEquals(11, abortedIndexList.get(1).firstOffset());
        assertEquals(2, abortedIndexList.size());


        // verify that we actually drop (only) one aborted TX
        long purged = partitionLog.forcePurgeAbortTx().get();
        assertEquals(purged, 1);

        // verify that we still have one aborted TX
        assertTrue(partitionLog.getProducerStateManager().hasSomeAbortedTransactions());
        abortedIndexList = partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
        abortedIndexList.forEach(tx -> {
            log.info("TX {}", tx);
        });
        assertEquals(1, abortedIndexList.size());
        assertEquals(11, abortedIndexList.get(0).firstOffset());

        // use a new consumer group, it will read from the beginning of the topic
        assertEquals(
                consumeTxnMessage(topicName, 3, lastMessage2, isolation, "second_group"),
                List.of("msg4", "msg5", "msg6"));

    }




    @Test(timeOut = 60000)
    public void testRecoverFromInvalidSnapshotAfterTrim() throws Exception {

        String topicName = "testRecoverFromInvalidSnapshotAfterTrim";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        String isolation = "read_committed";

        TopicName fullTopicName = TopicName.get(topicName);

        pulsar.getAdminClient().topics().createPartitionedTopic(topicName, 1);

        String namespace = fullTopicName.getNamespace();
        TopicPartition topicPartition = new TopicPartition(topicName, 0);
        String namespacePrefix = namespace;

        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);

        producer.initTransactions();

        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "aborted 1")).get(); // OFFSET 0
        producer.flush();
        producer.abortTransaction(); // OFFSET 1

        producer.beginTransaction();
        String lastMessage = "msg1b";
        producer.send(new ProducerRecord<>(topicName, 0, "msg1")).get(); // OFFSET 2
        producer.send(new ProducerRecord<>(topicName, 0, lastMessage)).get(); // OFFSET 3
        producer.commitTransaction(); // OFFSET 4

        assertEquals(
                consumeTxnMessage(topicName, 2, lastMessage, isolation, "first_group"),
                List.of("msg1", "msg1b"));

        waitForTransactionsToBeInStableState(transactionalId);

        // unload and reload in order to have at least 2 ledgers in the
        // topic, this way we can drop the head ledger
        admin.topics().unload(fullTopicName.getPartition(0).toString());
        admin.lookups().lookupTopic(fullTopicName.getPartition(0).toString());

        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "msg2")).get(); // OFFSET 5
        producer.send(new ProducerRecord<>(topicName, 0, "msg3")).get(); // OFFSET 6
        producer.commitTransaction();  // OFFSET 7

        // take a snapshot now, it refers to the offset of the last written record
        takeSnapshot(topicName);

        waitForTransactionsToBeInStableState(transactionalId);

        admin.topics().unload(fullTopicName.getPartition(0).toString());
        admin.lookups().lookupTopic(fullTopicName.getPartition(0).toString());

        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");
        PartitionLog partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);
        partitionLog.awaitInitialisation().get();
        assertEquals(0L, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

        // all the messages up to here will be trimmed

        trimConsumedLedgers(fullTopicName.getPartition(0).toString());

        admin.topics().unload(fullTopicName.getPartition(0).toString());

        // continue writing, this triggers recovery
        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 0, "msg4")).get();  // OFFSET 8
        producer.send(new ProducerRecord<>(topicName, 0, "msg5")).get();  // OFFSET 9
        producer.commitTransaction();  // OFFSET 10
        producer.close();

        partitionLog = protocolHandler
                .getReplicaManager()
                .getPartitionLog(topicPartition, namespacePrefix);
        partitionLog.awaitInitialisation().get();
        assertEquals(8L, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

        // use a new consumer group, it will read from the beginning of the topic
        assertEquals(
                consumeTxnMessage(topicName, 2, "msg5", isolation, "second_group"),
                List.of("msg4", "msg5"));

    }


    private List<String> prepareData(String sourceTopicName,
                                     String messageContent,
                                     int messageCount) throws ExecutionException, InterruptedException {
        // producer
        KafkaProducer<Integer, String> producer = buildIdempotenceProducer();

        List<String> sendMsgs = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            String msg = messageContent + i;
            sendMsgs.add(msg);
            producer.send(new ProducerRecord<>(sourceTopicName, i, msg)).get();
        }
        return sendMsgs;
    }

    private void waitForTxnMarkerWriteComplete(Map<TopicPartition, OffsetAndMetadata> offsets,
                                               KafkaConsumer<Integer, String> consumer) throws InterruptedException {
        AtomicBoolean flag = new AtomicBoolean();
        for (int i = 0; i < 5; i++) {
            flag.set(true);
            consumer.assignment().forEach(tp -> {
                OffsetAndMetadata offsetAndMetadata = consumer.committed(tp);
                if (offsetAndMetadata == null || !offsetAndMetadata.equals(offsets.get(tp))) {
                    flag.set(false);
                }
            });
            if (flag.get()) {
                break;
            }
            Thread.sleep(200);
        }
        if (!flag.get()) {
            fail("The txn markers are not wrote.");
        }
    }

    private static void resetToLastCommittedPositions(KafkaConsumer<Integer, String> consumer) {
        consumer.assignment().forEach(tp -> {
            OffsetAndMetadata offsetAndMetadata = consumer.committed(tp);
            if (offsetAndMetadata != null) {
                consumer.seek(tp, offsetAndMetadata.offset());
            } else {
                consumer.seekToBeginning(Collections.singleton(tp));
            }
        });
    }

    private KafkaProducer<Integer, String> buildTransactionProducer(String transactionalId) {
        return buildTransactionProducer(transactionalId, -1);
    }

    private KafkaProducer<Integer, String> buildTransactionProducer(String transactionalId, int txTimeout) {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getKafkaServerAdder());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000 * 10);
        producerProps.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        if (txTimeout > 0) {
            producerProps.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, txTimeout);
        } else {
            // very long time-out
            producerProps.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, TRANSACTION_TIMEOUT_CONFIG_VALUE);
        }
        producerProps.put(CLIENT_ID_CONFIG, "dummy_client_" + UUID.randomUUID());
        addCustomizeProps(producerProps);

        return new KafkaProducer<>(producerProps);
    }

    private KafkaConsumer<Integer, String> buildTransactionConsumer(String groupId, String isolation) {
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getKafkaServerAdder());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000 * 10);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        addCustomizeProps(consumerProps);

        return new KafkaConsumer<>(consumerProps);
    }

    private KafkaProducer<Integer, String> buildIdempotenceProducer() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getKafkaServerAdder());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000 * 10);
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        addCustomizeProps(producerProps);
        return new KafkaProducer<>(producerProps);
    }


    @Test(timeOut = 20000)
    public void testProducerFencedWhileSendFirstRecord() throws Exception {
        String topicName = "testProducerFencedWhileSendFirstRecord";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);
        producer1.initTransactions();
        producer1.beginTransaction();

        final KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId);
        producer2.initTransactions();
        producer2.beginTransaction();
        producer2.send(new ProducerRecord<>(topicName, "test")).get();

        assertThat(
                expectThrows(ExecutionException.class, () -> {
                    producer1.send(new ProducerRecord<>(topicName, "test"))
                            .get();
                }).getCause(), instanceOf(ProducerFencedException.class));

        producer1.close();
        producer2.close();
    }

    @Test(timeOut = 20000)
    public void testProducerFencedWhileCommitTransaction() throws Exception {
        String topicName = "testProducerFencedWhileCommitTransaction";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);
        producer1.initTransactions();
        producer1.beginTransaction();
        producer1.send(new ProducerRecord<>(topicName, "test"))
                .get();

        final KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId);
        producer2.initTransactions();
        producer2.beginTransaction();
        producer2.send(new ProducerRecord<>(topicName, "test")).get();


        // producer1 is still able to write (TODO: this should throw a InvalidProducerEpochException)
        producer1.send(new ProducerRecord<>(topicName, "test")).get();

        // but it cannot commit
        expectThrows(ProducerFencedException.class, () -> {
            producer1.commitTransaction();
        });

        // producer2 can commit
        producer2.commitTransaction();
        producer1.close();
        producer2.close();
    }

    @Test(timeOut = 20000)
    public void testProducerFencedWhileSendOffsets() throws Exception {
        String topicName = "testProducerFencedWhileSendOffsets";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);
        producer1.initTransactions();
        producer1.beginTransaction();
        producer1.send(new ProducerRecord<>(topicName, "test"))
                .get();

        final KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId);
        producer2.initTransactions();
        producer2.beginTransaction();
        producer2.send(new ProducerRecord<>(topicName, "test")).get();


        // producer1 cannot offsets
        expectThrows(ProducerFencedException.class, () -> {
            producer1.sendOffsetsToTransaction(ImmutableMap.of(new TopicPartition(topicName, 0),
                            new OffsetAndMetadata(0L)),
                    "testGroup");
        });

        // and it cannot commit
        expectThrows(ProducerFencedException.class, () -> {
            producer1.commitTransaction();
        });

        producer1.close();
        producer2.close();
    }

    @Test(timeOut = 20000)
    public void testProducerFencedWhileAbortAndBegin() throws Exception {
        String topicName = "testProducerFencedWhileAbortAndBegin";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);
        producer1.initTransactions();
        producer1.beginTransaction();
        producer1.send(new ProducerRecord<>(topicName, "test"))
                .get();

        final KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId);
        producer2.initTransactions();
        producer2.beginTransaction();
        producer2.send(new ProducerRecord<>(topicName, "test")).get();

        // producer1 cannot abort
        expectThrows(ProducerFencedException.class, () -> {
            producer1.abortTransaction();
        });

        // producer1 cannot start a new transaction
        expectThrows(ProducerFencedException.class, () -> {
            producer1.beginTransaction();
        });
        producer1.close();
        producer2.close();
    }

    @Test(timeOut = 20000)
    public void testNotFencedWithBeginTransaction() throws Exception {
        String topicName = "testNotFencedWithBeginTransaction";
        String transactionalId = "myProducer_" + UUID.randomUUID();
        final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);
        producer1.initTransactions();

        final KafkaProducer<Integer, String> producer2 = buildTransactionProducer(transactionalId);
        producer2.initTransactions();
        producer2.beginTransaction();
        producer2.send(new ProducerRecord<>(topicName, "test")).get();

        // beginTransaction doesn't do anything
        producer1.beginTransaction();

        producer1.close();
        producer2.close();
    }

    @Test(timeOut = 20000)
    public void testSnapshotEventuallyTaken() throws Exception {
        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");
        int kafkaTxnPurgeAbortedTxnIntervalSeconds = conf.getKafkaTxnPurgeAbortedTxnIntervalSeconds();
        conf.setKafkaTxnProducerStateTopicSnapshotIntervalSeconds(2);
        try {
            String topicName = "testSnapshotEventuallyTaken";
            TopicName topicName1 = TopicName.get(topicName);
            String fullTopicName = topicName1.getPartition(0).toString();
            String transactionalId = "myProducer_" + UUID.randomUUID();

            @Cleanup
            AdminClient kafkaAdmin = AdminClient.create(newKafkaAdminClientProperties());
            kafkaAdmin.createTopics(Arrays.asList(new NewTopic(topicName, 1, (short) 1))).all().get();

            // no snapshot initially
            assertNull(protocolHandler.getTransactionCoordinator(tenant)
                    .getProducerStateManagerSnapshotBuffer()
                    .readLatestSnapshot(fullTopicName)
                    .get());

            final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);

            producer1.initTransactions();
            producer1.beginTransaction();
            producer1.send(new ProducerRecord<>(topicName, "test")); // OFFSET 0
            producer1.commitTransaction(); // OFFSET 1

            producer1.beginTransaction();
            producer1.send(new ProducerRecord<>(topicName, "test")); // OFFSET 2 - first offset
            producer1.send(new ProducerRecord<>(topicName, "test")).get(); // OFFSET 3

            Thread.sleep(conf.getKafkaTxnProducerStateTopicSnapshotIntervalSeconds() * 1000 + 5);

            // sending a message triggers the creation of the snapshot
            producer1.send(new ProducerRecord<>(topicName, "test")).get(); // OFFSET 4

            // snapshot is written and sent to Pulsar async and also the ProducerStateManagerSnapshotBuffer
            // reads it asynchronously

            Awaitility
                    .await()
                    .pollDelay(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                ProducerStateManagerSnapshot snapshot = protocolHandler.getTransactionCoordinator(tenant)
                        .getProducerStateManagerSnapshotBuffer()
                        .readLatestSnapshot(fullTopicName)
                        .get();

                assertNotNull(snapshot);
                assertEquals(4, snapshot.getOffset());
                assertEquals(1, snapshot.getProducers().size());
                assertEquals(1, snapshot.getOngoingTxns().size());
                assertNotNull(snapshot.getTopicUUID());
                TxnMetadata txnMetadata = snapshot.getOngoingTxns().values().iterator().next();
                assertEquals(txnMetadata.firstOffset(), 2);
            });

            producer1.close();
        } finally {
            conf.setKafkaTxnProducerStateTopicSnapshotIntervalSeconds(kafkaTxnPurgeAbortedTxnIntervalSeconds);
        }
    }

    @Test(timeOut = 30000)
    public void testAbortedTxEventuallyPurged() throws Exception {
        KafkaProtocolHandler protocolHandler = (KafkaProtocolHandler)
                pulsar.getProtocolHandlers().protocol("kafka");
        int kafkaTxnPurgeAbortedTxnIntervalSeconds = conf.getKafkaTxnPurgeAbortedTxnIntervalSeconds();
        conf.setKafkaTxnPurgeAbortedTxnIntervalSeconds(2);
        try {
            String topicName = "testAbortedTxEventuallyPurged";
            TopicName topicName1 = TopicName.get(topicName);
            String fullTopicName = topicName1.getPartition(0).toString();
            String transactionalId = "myProducer_" + UUID.randomUUID();
            TopicPartition topicPartition = new TopicPartition(topicName, 0);
            String namespacePrefix = topicName1.getNamespace();

            @Cleanup
            AdminClient kafkaAdmin = AdminClient.create(newKafkaAdminClientProperties());
            kafkaAdmin.createTopics(Arrays.asList(new NewTopic(topicName, 1, (short) 1))).all().get();

            final KafkaProducer<Integer, String> producer1 = buildTransactionProducer(transactionalId);

            producer1.initTransactions();
            producer1.beginTransaction();
            producer1.send(new ProducerRecord<>(topicName, "test")).get(); // OFFSET 0
            producer1.send(new ProducerRecord<>(topicName, "test")).get(); // OFFSET 1
            producer1.abortTransaction(); // OFFSET 2

            producer1.beginTransaction();
            producer1.send(new ProducerRecord<>(topicName, "test")).get(); // OFFSET 3
            producer1.send(new ProducerRecord<>(topicName, "test")).get(); // OFFSET 4
            producer1.abortTransaction(); // OFFSET 5

            waitForTransactionsToBeInStableState(transactionalId);

            PartitionLog partitionLog = protocolHandler
                    .getReplicaManager()
                    .getPartitionLog(topicPartition, namespacePrefix);
            partitionLog.awaitInitialisation().get();

            List<FetchResponseData.AbortedTransaction> abortedIndexList =
                    partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
            assertEquals(2, abortedIndexList.size());
            assertEquals(2, abortedIndexList.size());
            assertEquals(0, abortedIndexList.get(0).firstOffset());
            assertEquals(3, abortedIndexList.get(1).firstOffset());

            takeSnapshot(topicName);


            assertEquals(0, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

            // unload and reload in order to have at least 2 ledgers in the
            // topic, this way we can drop the head ledger
            admin.topics().unload(fullTopicName);
            admin.lookups().lookupTopic(fullTopicName);

            assertTrue(partitionLog.isUnloaded());

            trimConsumedLedgers(fullTopicName);

            partitionLog = protocolHandler
                    .getReplicaManager()
                    .getPartitionLog(topicPartition, namespacePrefix);
            partitionLog.awaitInitialisation().get();
            assertEquals(5, partitionLog.fetchOldestAvailableIndexFromTopic().get().longValue());

            abortedIndexList =
                    partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
            assertEquals(2, abortedIndexList.size());
            assertEquals(0, abortedIndexList.get(0).firstOffset());
            assertEquals(3, abortedIndexList.get(1).firstOffset());

            // force reading the minimum valid offset
            // the timer is not started by the PH because
            // we don't want it to make noise in the other tests
            partitionLog.updatePurgeAbortedTxnsOffset().get();

            // wait for some time
            Thread.sleep(conf.getKafkaTxnPurgeAbortedTxnIntervalSeconds() * 1000 + 5);

            producer1.beginTransaction();
            // sending a message triggers the procedure
            producer1.send(new ProducerRecord<>(topicName, "test")).get();

            abortedIndexList =
                    partitionLog.getProducerStateManager().getAbortedIndexList(Long.MIN_VALUE);
            assertEquals(1, abortedIndexList.size());
            // the second TX cannot be purged because the lastOffset is 5, that is the boundary of the
            // trimmed portion of the topic
            assertEquals(3, abortedIndexList.get(0).firstOffset());

            producer1.close();

        } finally {
            conf.setKafkaTxnPurgeAbortedTxnIntervalSeconds(kafkaTxnPurgeAbortedTxnIntervalSeconds);
        }
    }

    @Test(timeOut = 1000 * 30)
    public void testListAndDescribeTransactions() throws Exception {

        String topicName = "testListAndDescribeTransactions";
        String transactionalId = "myProducer_" + UUID.randomUUID();

        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);
        @Cleanup
        AdminClient kafkaAdmin = AdminClient.create(newKafkaAdminClientProperties());

        producer.initTransactions();
        producer.beginTransaction();
        assertTransactionState(kafkaAdmin, transactionalId,
            org.apache.kafka.clients.admin.TransactionState.EMPTY, (stateOnBroker, stateOnCoodinator) -> {
               assertNull(stateOnBroker);
            });
        producer.send(new ProducerRecord<>(topicName, 1, "bar")).get();
        producer.flush();

        // the transaction is in ONGOING state
        assertTransactionState(kafkaAdmin, transactionalId,
                org.apache.kafka.clients.admin.TransactionState.ONGOING,
                (stateOnBroker, stateOnCoodinator) -> {});

        // wait for the brokers to update the state
        Awaitility.await().untilAsserted(() -> {
             assertTransactionState(kafkaAdmin, transactionalId,
                org.apache.kafka.clients.admin.TransactionState.ONGOING,
                (stateOnBroker, stateOnCoodinator) -> {
                     // THESE ASSERTIONS ARE NOT VALID YET
                     //log.info("stateOnBroker: {}", stateOnBroker);
                     //log.info("stateOnCoodinator: {}", stateOnCoodinator);
                     // assertTrue(stateOnBroker.lastTimestamp()
                     //       >= stateOnCoodinator.transactionStartTimeMs().orElseThrow());
                });
        });
        producer.commitTransaction();
        Awaitility.await().untilAsserted(() -> {
                    assertTransactionState(kafkaAdmin, transactionalId,
                            org.apache.kafka.clients.admin.TransactionState.COMPLETE_COMMIT,
                            (stateOnBroker, stateOnCoodinator) -> {
                            });
                });
        producer.beginTransaction();

        assertTransactionState(kafkaAdmin, transactionalId,
                org.apache.kafka.clients.admin.TransactionState.COMPLETE_COMMIT,
                (stateOnBroker, stateOnCoodinator) -> {
                });

        producer.send(new ProducerRecord<>(topicName, 1, "bar")).get();
        producer.flush();
        producer.abortTransaction();
        Awaitility.await().untilAsserted(() -> {
            assertTransactionState(kafkaAdmin, transactionalId,
                    org.apache.kafka.clients.admin.TransactionState.COMPLETE_ABORT,
                    (stateOnBroker, stateOnCoodinator) -> {
                    });
        });
        producer.close();
        assertTransactionState(kafkaAdmin, transactionalId,
                org.apache.kafka.clients.admin.TransactionState.COMPLETE_ABORT,
                (stateOnBroker, stateOnCoodinator) -> {
                });
    }

    private static void assertTransactionState(AdminClient kafkaAdmin, String transactionalId,
                                               org.apache.kafka.clients.admin.TransactionState transactionState,
                                               BiConsumer<ProducerState, TransactionDescription>
                                               producerStateValidator)
            throws Exception {
        ListTransactionsResult listTransactionsResult = kafkaAdmin.listTransactions();
        Collection<TransactionListing> transactionListings = listTransactionsResult.all().get();
        transactionListings.forEach(t -> {
            log.info("Found transactionalId: {} {} {}",
                    t.transactionalId(),
                    t.producerId(),
                    t.state());
        });
        TransactionListing transactionListing = transactionListings
                .stream()
                .filter(t -> t.transactionalId().equals(transactionalId))
                .findFirst()
                .get();
        assertEquals(transactionState, transactionListing.state());

        // filter for the same state
        ListTransactionsOptions optionFilterState = new ListTransactionsOptions()
                .filterStates(Collections.singleton(transactionState));
        listTransactionsResult = kafkaAdmin.listTransactions(optionFilterState);
        transactionListings = listTransactionsResult.all().get();
        transactionListing = transactionListings
                .stream()
                .filter(t -> t.transactionalId().equals(transactionalId))
                .findFirst()
                .get();
        assertEquals(transactionState, transactionListing.state());


        // filter for the same producer id
        ListTransactionsOptions optionFilterProducer = new ListTransactionsOptions()
                .filterProducerIds(Collections.singleton(transactionListing.producerId()));
        listTransactionsResult = kafkaAdmin.listTransactions(optionFilterProducer);
        transactionListings = listTransactionsResult.all().get();
        transactionListing = transactionListings
                .stream()
                .filter(t -> t.transactionalId().equals(transactionalId))
                .findFirst()
                .get();
        assertEquals(transactionState, transactionListing.state());

        // filter for the same producer id and state
        ListTransactionsOptions optionFilterProducerAndState = new ListTransactionsOptions()
                .filterStates(Collections.singleton(transactionState))
                .filterProducerIds(Collections.singleton(transactionListing.producerId()));
        listTransactionsResult = kafkaAdmin.listTransactions(optionFilterProducerAndState);
        transactionListings = listTransactionsResult.all().get();
        transactionListing = transactionListings
                .stream()
                .filter(t -> t.transactionalId().equals(transactionalId))
                .findFirst()
                .get();
        assertEquals(transactionState, transactionListing.state());

        Map<String, TransactionDescription> map =
                kafkaAdmin.describeTransactions(Collections.singleton(transactionalId))
                .all().get();
        assertEquals(1, map.size());
        TransactionDescription transactionDescription = map.get(transactionalId);
        log.info("transactionDescription {}", transactionDescription);
        assertNotNull(transactionDescription);
        assertEquals(transactionDescription.state(), transactionState);
        assertTrue(transactionDescription.producerEpoch() >= 0);
        assertEquals(TRANSACTION_TIMEOUT_CONFIG_VALUE, transactionDescription.transactionTimeoutMs());
        assertTrue(transactionDescription.transactionStartTimeMs().isPresent());
        assertTrue(transactionDescription.coordinatorId() >= 0);

        switch (transactionState) {
            case EMPTY:
            case COMPLETE_COMMIT:
            case COMPLETE_ABORT:
                assertEquals(0, transactionDescription.topicPartitions().size());
                break;
            case ONGOING:
            case PREPARE_ABORT:
                assertTrue(transactionDescription.transactionStartTimeMs().orElseThrow() > 0);
                assertEquals(1, transactionDescription.topicPartitions().size());
                break;
            default:
                fail("unhandled " + transactionState);
        }

        DescribeProducersResult producers = kafkaAdmin.describeProducers(transactionDescription.topicPartitions());
        Map<TopicPartition, DescribeProducersResult.PartitionProducerState> topicPartitionPartitionProducerStateMap =
                producers.all().get();
        log.debug("topicPartitionPartitionProducerStateMap {}", topicPartitionPartitionProducerStateMap);


        switch (transactionState) {
            case EMPTY:
            case COMPLETE_COMMIT:
            case COMPLETE_ABORT:
                producerStateValidator.accept(null, transactionDescription);
                assertEquals(0, topicPartitionPartitionProducerStateMap.size());
                break;
            case ONGOING:
            case PREPARE_ABORT:
                assertEquals(1, topicPartitionPartitionProducerStateMap.size());
                TopicPartition tp = transactionDescription.topicPartitions().iterator().next();
                DescribeProducersResult.PartitionProducerState partitionProducerState =
                        topicPartitionPartitionProducerStateMap.get(tp);
                List<ProducerState> producerStates = partitionProducerState.activeProducers();
                assertEquals(1, producerStates.size());
                ProducerState producerState = producerStates.get(0);
                assertEquals(producerState.producerId(), transactionDescription.producerId());
                producerStateValidator.accept(producerState, transactionDescription);


                break;
            default:
                fail("unhandled " + transactionState);
        }


    }

    @Test(timeOut = 1000 * 30)
    public void testAbortTransactinsFromAdmin() throws Exception {

        String topicName = "testAbortTransactinsFromAdmin";
        String transactionalId = "myProducer_" + UUID.randomUUID();

        @Cleanup
        KafkaProducer<Integer, String> producer = buildTransactionProducer(transactionalId);
        @Cleanup
        AdminClient kafkaAdmin = AdminClient.create(newKafkaAdminClientProperties());
        kafkaAdmin.createTopics(Arrays.asList(new NewTopic(topicName, 1, (short) 1)))
                .all().get();

        producer.initTransactions();
        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topicName, 1, "bar")).get();
        producer.flush();

        // the transaction is in ONGOING state
        assertTransactionState(kafkaAdmin, transactionalId,
                org.apache.kafka.clients.admin.TransactionState.ONGOING,
                (stateOnBroker, stateOnCoodinator) -> {
                });

        TopicPartition topicPartition = new TopicPartition(topicName, 0);

        DescribeProducersResult.PartitionProducerState partitionProducerState =
                kafkaAdmin.describeProducers(Collections.singletonList(topicPartition))
                .partitionResult(topicPartition).get();
        ProducerState producerState = partitionProducerState.activeProducers().get(0);

        // we send the ABORT transaction marker to the broker
        kafkaAdmin.abortTransaction(new AbortTransactionSpec(topicPartition,
                producerState.producerId(),
                (short) producerState.producerEpoch(),
                producerState.coordinatorEpoch().orElse(-1))).all().get();

        // the coordinator isn't aware of the operation sent to the brokers
        // so it allows to abort the transaction
        producer.commitTransaction();

        producer.close();

        // the transaction is eventually committed
        Awaitility.await().untilAsserted(() -> {
            assertTransactionState(kafkaAdmin, transactionalId,
                    org.apache.kafka.clients.admin.TransactionState.COMPLETE_COMMIT,
                    (stateOnBroker, stateOnCoodinator) -> {
                    });
        });
    }

    /**
     * Get the Kafka server address.
     */
    private String getKafkaServerAdder() {
        return "localhost:" + getClientPort();
    }

    protected void addCustomizeProps(Properties producerProps) {
        // No-op
    }

    @DataProvider(name = "isolationProvider")
    protected Object[][] isolationProvider() {
        return new Object[][]{
                {"read_committed"},
                {"read_uncommitted"},
        };
    }

    @Test(dataProvider = "isolationProvider", timeOut = 1000 * 30)
    public void readUnstableMessagesTest(String isolation) throws InterruptedException, ExecutionException {
        String topic = "unstable-message-test-" + RandomStringUtils.randomAlphabetic(5);

        KafkaConsumer<Integer, String> consumer = buildTransactionConsumer("unstable-read", isolation);
        consumer.subscribe(Collections.singleton(topic));

        String tnxId = "txn-" + RandomStringUtils.randomAlphabetic(5);
        KafkaProducer<Integer, String> producer = buildTransactionProducer(tnxId);
        producer.initTransactions();

        String baseMsg = "test msg commit - ";
        producer.beginTransaction();
        producer.send(new ProducerRecord<>(topic, baseMsg + 0)).get();
        producer.send(new ProducerRecord<>(topic, baseMsg + 1)).get();
        producer.flush();

        AtomicInteger messageCount = new AtomicInteger(0);
        // make sure consumer can't receive unstable messages in `read_committed` mode
        readAndCheckMessages(consumer, baseMsg, messageCount, isolation.equals("read_committed") ? 0 : 2);

        producer.commitTransaction();
        producer.beginTransaction();
        // these two unstable message shouldn't be received in `read_committed` mode
        producer.send(new ProducerRecord<>(topic, baseMsg + 2)).get();
        producer.send(new ProducerRecord<>(topic, baseMsg + 3)).get();
        producer.flush();

        readAndCheckMessages(consumer, baseMsg, messageCount, isolation.equals("read_committed") ? 2 : 4);

        consumer.close();
        producer.close();
    }

    private void readAndCheckMessages(KafkaConsumer<Integer, String> consumer, String baseMsg,
                                      AtomicInteger messageCount, int expectedMessageCount) {
        while (true) {
            ConsumerRecords<Integer, String> records = consumer.poll(Duration.ofSeconds(3));
            if (records.isEmpty()) {
                break;
            }
            for (ConsumerRecord<Integer, String> record : records) {
                assertEquals(record.value(), baseMsg + messageCount.getAndIncrement());
            }
        }
        // make sure there is no message can be received
        ConsumerRecords<Integer, String> records = consumer.poll(Duration.ofSeconds(3));
        assertTrue(records.isEmpty());
        // make sure only receive the expected number of stable messages
        assertEquals(messageCount.get(), expectedMessageCount);
    }

}
