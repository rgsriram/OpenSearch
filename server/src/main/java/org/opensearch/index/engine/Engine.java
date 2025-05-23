/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.engine;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.StandardDirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.QueryCache;
import org.apache.lucene.search.QueryCachingPolicy;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.opensearch.ExceptionsHelper;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.Nullable;
import org.opensearch.common.SetOnce;
import org.opensearch.common.annotation.PublicApi;
import org.opensearch.common.concurrent.GatedCloseable;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.lease.Releasables;
import org.opensearch.common.logging.Loggers;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.common.lucene.index.OpenSearchDirectoryReader;
import org.opensearch.common.lucene.search.Queries;
import org.opensearch.common.lucene.uid.Versions;
import org.opensearch.common.lucene.uid.VersionsAndSeqNoResolver;
import org.opensearch.common.lucene.uid.VersionsAndSeqNoResolver.DocIdAndVersion;
import org.opensearch.common.metrics.CounterMetric;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ReleasableLock;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.VersionType;
import org.opensearch.index.mapper.IdFieldMapper;
import org.opensearch.index.mapper.Mapping;
import org.opensearch.index.mapper.ParseContext.Document;
import org.opensearch.index.mapper.ParsedDocument;
import org.opensearch.index.mapper.SeqNoFieldMapper;
import org.opensearch.index.merge.MergeStats;
import org.opensearch.index.seqno.SeqNoStats;
import org.opensearch.index.seqno.SequenceNumbers;
import org.opensearch.index.shard.DocsStats;
import org.opensearch.index.store.Store;
import org.opensearch.index.translog.DefaultTranslogDeletionPolicy;
import org.opensearch.index.translog.Translog;
import org.opensearch.index.translog.TranslogDeletionPolicy;
import org.opensearch.index.translog.TranslogManager;
import org.opensearch.indices.pollingingest.PollingIngestStats;
import org.opensearch.search.suggest.completion.CompletionStats;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_PRIMARY_TERM;
import static org.opensearch.index.seqno.SequenceNumbers.UNASSIGNED_SEQ_NO;

/**
 * Base OpenSearch Engine class
 *
 * @opensearch.api
 */
@PublicApi(since = "1.0.0")
public abstract class Engine implements LifecycleAware, Closeable {

    public static final String SYNC_COMMIT_ID = "sync_id";  // TODO: remove sync_id in 3.0
    public static final String HISTORY_UUID_KEY = "history_uuid";
    public static final String FORCE_MERGE_UUID_KEY = "force_merge_uuid";
    public static final String MIN_RETAINED_SEQNO = "min_retained_seq_no";
    public static final String MAX_UNSAFE_AUTO_ID_TIMESTAMP_COMMIT_ID = "max_unsafe_auto_id_timestamp";
    public static final String SEARCH_SOURCE = "search"; // TODO: Make source of search enum?
    public static final String CAN_MATCH_SEARCH_SOURCE = "can_match";
    public static final String FORCE_MERGE = "force merge";
    public static final String MERGE_FAILED = "merge failed";

    protected final ShardId shardId;
    protected final Logger logger;
    protected final EngineConfig engineConfig;
    protected final Store store;
    protected final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final CounterMetric totalUnreferencedFileCleanUpsPerformed = new CounterMetric();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    protected final EventListener eventListener;
    protected final ReentrantLock failEngineLock = new ReentrantLock();
    protected final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    protected final ReleasableLock readLock = new ReleasableLock(rwl.readLock());
    protected final ReleasableLock writeLock = new ReleasableLock(rwl.writeLock());
    protected final SetOnce<Exception> failedEngine = new SetOnce<>();
    /*
     * on {@code lastWriteNanos} we use System.nanoTime() to initialize this since:
     *  - we use the value for figuring out if the shard / engine is active so if we startup and no write has happened yet we still
     *    consider it active for the duration of the configured active to inactive period. If we initialize to 0 or Long.MAX_VALUE we
     *    either immediately or never mark it inactive if no writes at all happen to the shard.
     *  - we also use this to flush big-ass merges on an inactive engine / shard but if we we initialize 0 or Long.MAX_VALUE we either
     *    immediately or never commit merges even though we shouldn't from a user perspective (this can also have funky side effects in
     *    tests when we open indices with lots of segments and suddenly merges kick in.
     *  NOTE: don't use this value for anything accurate it's a best effort for freeing up diskspace after merges and on a shard level to
     *  reduce index buffer sizes on inactive shards.
     */
    protected volatile long lastWriteNanos = System.nanoTime();

    protected Engine(EngineConfig engineConfig) {
        Objects.requireNonNull(engineConfig.getStore(), "Store must be provided to the engine");

        this.engineConfig = engineConfig;
        this.shardId = engineConfig.getShardId();
        this.store = engineConfig.getStore();
        // we use the engine class directly here to make sure all subclasses have the same logger name
        this.logger = Loggers.getLogger(Engine.class, engineConfig.getShardId());
        this.eventListener = engineConfig.getEventListener();
    }

    public final EngineConfig config() {
        return engineConfig;
    }

    public abstract TranslogManager translogManager();

    protected abstract SegmentInfos getLastCommittedSegmentInfos();

    /**
     * Return the latest active SegmentInfos from the engine.
     * @return {@link SegmentInfos}
     */
    @Nullable
    protected abstract SegmentInfos getLatestSegmentInfos();

    /**
     * In contrast to {@link #getLatestSegmentInfos()}, which returns a {@link SegmentInfos}
     * object directly, this method returns a {@link GatedCloseable} reference to the same object.
     * This allows the engine to include a clean-up {@link org.opensearch.common.CheckedRunnable}
     * which is run when the reference is closed. The default implementation of the clean-up
     * procedure is a no-op.
     *
     * @return {@link GatedCloseable} - A wrapper around a {@link SegmentInfos} instance that
     * must be closed for segment files to be deleted.
     */
    public GatedCloseable<SegmentInfos> getSegmentInfosSnapshot() {
        // default implementation
        return new GatedCloseable<>(getLatestSegmentInfos(), () -> {});
    }

    public MergeStats getMergeStats() {
        return new MergeStats();
    }

    /** returns the history uuid for the engine */
    public abstract String getHistoryUUID();

    /**
     * Reads the current stored history ID from commit data.
     */
    String loadHistoryUUID(Map<String, String> commitData) {
        final String uuid = commitData.get(HISTORY_UUID_KEY);
        if (uuid == null) {
            throw new IllegalStateException("commit doesn't contain history uuid");
        }
        return uuid;
    }

    /** Returns how many bytes we are currently moving from heap to disk */
    public abstract long getWritingBytes();

    /**
     * Returns the {@link CompletionStats} for this engine
     */
    public abstract CompletionStats completionStats(String... fieldNamePatterns);

    /**
     * Returns the {@link DocsStats} for this engine
     */
    public DocsStats docStats() {
        // we calculate the doc stats based on the internal searcher that is more up-to-date and not subject
        // to external refreshes. For instance we don't refresh an external searcher if we flush and indices with
        // index.refresh_interval=-1 won't see any doc stats updates at all. This change will give more accurate statistics
        // when indexing but not refreshing in general. Yet, if a refresh happens the internal searcher is refresh as well so we are
        // safe here.
        try (Searcher searcher = acquireSearcher("docStats", SearcherScope.INTERNAL)) {
            return docsStats(searcher.getIndexReader());
        }
    }

    protected final DocsStats docsStats(IndexReader indexReader) {
        long numDocs = 0;
        long numDeletedDocs = 0;
        long sizeInBytes = 0;
        // we don't wait for a pending refreshes here since it's a stats call instead we mark it as accessed only which will cause
        // the next scheduled refresh to go through and refresh the stats as well
        for (LeafReaderContext readerContext : indexReader.leaves()) {
            // we go on the segment level here to get accurate numbers
            final SegmentReader segmentReader = Lucene.segmentReader(readerContext.reader());
            SegmentCommitInfo info = segmentReader.getSegmentInfo();
            numDocs += readerContext.reader().numDocs();
            numDeletedDocs += readerContext.reader().numDeletedDocs();
            try {
                sizeInBytes += info.sizeInBytes();
            } catch (IOException e) {
                logger.trace(() -> new ParameterizedMessage("failed to get size for [{}]", info.info.name), e);
            }
        }
        return new DocsStats(numDocs, numDeletedDocs, sizeInBytes);
    }

    /**
     * Returns the unreferenced file cleanup count for this engine.
     */
    public long unreferencedFileCleanUpsPerformed() {
        return totalUnreferencedFileCleanUpsPerformed.count();
    }

    /**
     * Performs the pre-closing checks on the {@link Engine}.
     *
     * @throws IllegalStateException if the sanity checks failed
     */
    public void verifyEngineBeforeIndexClosing() throws IllegalStateException {
        final long globalCheckpoint = engineConfig.getGlobalCheckpointSupplier().getAsLong();
        final long maxSeqNo = getSeqNoStats(globalCheckpoint).getMaxSeqNo();
        if (globalCheckpoint != maxSeqNo) {
            throw new IllegalStateException(
                "Global checkpoint ["
                    + globalCheckpoint
                    + "] mismatches maximum sequence number ["
                    + maxSeqNo
                    + "] on index shard "
                    + shardId
            );
        }
    }

    /**
     * Get max sequence number from segments that are referenced by given SegmentInfos
     */
    public long getMaxSeqNoFromSegmentInfos(SegmentInfos segmentInfos) throws IOException {
        try (DirectoryReader innerReader = StandardDirectoryReader.open(store.directory(), segmentInfos, null, null)) {
            final IndexSearcher searcher = new IndexSearcher(innerReader);
            return getMaxSeqNoFromSearcher(searcher);
        }
    }

    /**
     * Get max sequence number that is part of given searcher. Sequence number is part of each document that is indexed.
     * This method fetches the _id of last indexed document that was part of the given searcher and
     * retrieves the _seq_no of the retrieved document.
     */
    protected long getMaxSeqNoFromSearcher(IndexSearcher searcher) throws IOException {
        searcher.setQueryCache(null);
        ScoreDoc[] docs = searcher.search(
            Queries.newMatchAllQuery(),
            1,
            new Sort(new SortField(SeqNoFieldMapper.NAME, SortField.Type.LONG, true))
        ).scoreDocs;
        if (docs.length == 0) {
            return SequenceNumbers.NO_OPS_PERFORMED;
        }
        org.apache.lucene.document.Document document = searcher.storedFields().document(docs[0].doc);
        Term uidTerm = new Term(IdFieldMapper.NAME, document.getField(IdFieldMapper.NAME).binaryValue());
        VersionsAndSeqNoResolver.DocIdAndVersion docIdAndVersion = VersionsAndSeqNoResolver.loadDocIdAndVersion(
            searcher.getIndexReader(),
            uidTerm,
            true
        );
        assert docIdAndVersion != null;
        return docIdAndVersion.seqNo;
    }

    /**
     * A throttling class that can be activated, causing the
     * {@code acquireThrottle} method to block on a lock when throttling
     * is enabled
     *
     * @opensearch.internal
     */
    protected static final class IndexThrottle {
        private final CounterMetric throttleTimeMillisMetric = new CounterMetric();
        private volatile long startOfThrottleNS;
        private static final ReleasableLock NOOP_LOCK = new ReleasableLock(new NoOpLock());
        private final ReleasableLock lockReference = new ReleasableLock(new ReentrantLock());
        private volatile ReleasableLock lock = NOOP_LOCK;

        public Releasable acquireThrottle() {
            return lock.acquire();
        }

        /** Activate throttling, which switches the lock to be a real lock */
        public void activate() {
            assert lock == NOOP_LOCK : "throttling activated while already active";
            startOfThrottleNS = System.nanoTime();
            lock = lockReference;
        }

        /** Deactivate throttling, which switches the lock to be an always-acquirable NoOpLock */
        public void deactivate() {
            assert lock != NOOP_LOCK : "throttling deactivated but not active";
            lock = NOOP_LOCK;

            assert startOfThrottleNS > 0 : "Bad state of startOfThrottleNS";
            long throttleTimeNS = System.nanoTime() - startOfThrottleNS;
            if (throttleTimeNS >= 0) {
                // Paranoia (System.nanoTime() is supposed to be monotonic): time slip may have occurred but never want
                // to add a negative number
                throttleTimeMillisMetric.inc(TimeValue.nsecToMSec(throttleTimeNS));
            }
        }

        long getThrottleTimeInMillis() {
            long currentThrottleNS = 0;
            if (isThrottled() && startOfThrottleNS != 0) {
                currentThrottleNS += System.nanoTime() - startOfThrottleNS;
                if (currentThrottleNS < 0) {
                    // Paranoia (System.nanoTime() is supposed to be monotonic): time slip must have happened, have to ignore this value
                    currentThrottleNS = 0;
                }
            }
            return throttleTimeMillisMetric.count() + TimeValue.nsecToMSec(currentThrottleNS);
        }

        boolean isThrottled() {
            return lock != NOOP_LOCK;
        }

        boolean throttleLockIsHeldByCurrentThread() { // to be used in assertions and tests only
            if (isThrottled()) {
                return lock.isHeldByCurrentThread();
            }
            return false;
        }
    }

    /**
     * Returns the number of milliseconds this engine was under index throttling.
     */
    public abstract long getIndexThrottleTimeInMillis();

    /**
     * Returns the <code>true</code> iff this engine is currently under index throttling.
     * @see #getIndexThrottleTimeInMillis()
     */
    public abstract boolean isThrottled();

    /**
     * A Lock implementation that always allows the lock to be acquired
     *
     * @opensearch.internal
     */
    protected static final class NoOpLock implements Lock {

        @Override
        public void lock() {}

        @Override
        public void lockInterruptibly() throws InterruptedException {}

        @Override
        public boolean tryLock() {
            return true;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public void unlock() {}

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException("NoOpLock can't provide a condition");
        }
    }

    /**
     * Perform document index operation on the engine
     * @param index operation to perform
     * @return {@link IndexResult} containing updated translog location, version and
     * document specific failures
     *
     * Note: engine level failures (i.e. persistent engine failures) are thrown
     */
    public abstract IndexResult index(Index index) throws IOException;

    /**
     * Perform document delete operation on the engine
     * @param delete operation to perform
     * @return {@link DeleteResult} containing updated translog location, version and
     * document specific failures
     *
     * Note: engine level failures (i.e. persistent engine failures) are thrown
     */
    public abstract DeleteResult delete(Delete delete) throws IOException;

    public abstract NoOpResult noOp(NoOp noOp) throws IOException;

    /**
     * Base class for index and delete operation results
     * Holds result meta data (e.g. translog location, updated version)
     * for an executed write {@link Operation}
     *
     * @opensearch.api
     **/
    @PublicApi(since = "1.0.0")
    public abstract static class Result {
        private final Operation.TYPE operationType;
        private final Result.Type resultType;
        private final long version;
        private final long term;
        private final long seqNo;
        private final Exception failure;
        private final SetOnce<Boolean> freeze = new SetOnce<>();
        private final Mapping requiredMappingUpdate;
        private Translog.Location translogLocation;
        private long took;

        protected Result(Operation.TYPE operationType, Exception failure, long version, long term, long seqNo) {
            this.operationType = operationType;
            this.failure = Objects.requireNonNull(failure);
            this.version = version;
            this.term = term;
            this.seqNo = seqNo;
            this.requiredMappingUpdate = null;
            this.resultType = Type.FAILURE;
        }

        protected Result(Operation.TYPE operationType, long version, long term, long seqNo) {
            this.operationType = operationType;
            this.version = version;
            this.seqNo = seqNo;
            this.term = term;
            this.failure = null;
            this.requiredMappingUpdate = null;
            this.resultType = Type.SUCCESS;
        }

        protected Result(Operation.TYPE operationType, Mapping requiredMappingUpdate) {
            this.operationType = operationType;
            this.version = Versions.NOT_FOUND;
            this.seqNo = UNASSIGNED_SEQ_NO;
            this.term = UNASSIGNED_PRIMARY_TERM;
            this.failure = null;
            this.requiredMappingUpdate = requiredMappingUpdate;
            this.resultType = Type.MAPPING_UPDATE_REQUIRED;
        }

        /** whether the operation was successful, has failed or was aborted due to a mapping update */
        public Type getResultType() {
            return resultType;
        }

        /** get the updated document version */
        public long getVersion() {
            return version;
        }

        /**
         * Get the sequence number on the primary.
         *
         * @return the sequence number
         */
        public long getSeqNo() {
            return seqNo;
        }

        public long getTerm() {
            return term;
        }

        /**
         * If the operation was aborted due to missing mappings, this method will return the mappings
         * that are required to complete the operation.
         */
        public Mapping getRequiredMappingUpdate() {
            return requiredMappingUpdate;
        }

        /** get the translog location after executing the operation */
        public Translog.Location getTranslogLocation() {
            return translogLocation;
        }

        /** get document failure while executing the operation {@code null} in case of no failure */
        public Exception getFailure() {
            return failure;
        }

        /** get total time in nanoseconds */
        public long getTook() {
            return took;
        }

        public Operation.TYPE getOperationType() {
            return operationType;
        }

        void setTranslogLocation(Translog.Location translogLocation) {
            if (freeze.get() == null) {
                this.translogLocation = translogLocation;
            } else {
                throw new IllegalStateException("result is already frozen");
            }
        }

        void setTook(long took) {
            if (freeze.get() == null) {
                this.took = took;
            } else {
                throw new IllegalStateException("result is already frozen");
            }
        }

        void freeze() {
            freeze.set(true);
        }

        /**
         * Type of the result
         *
         * @opensearch.api
         */
        @PublicApi(since = "1.0.0")
        public enum Type {
            SUCCESS,
            FAILURE,
            MAPPING_UPDATE_REQUIRED
        }
    }

    /**
     * Index result
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class IndexResult extends Result {

        private final boolean created;

        public IndexResult(long version, long term, long seqNo, boolean created) {
            super(Operation.TYPE.INDEX, version, term, seqNo);
            this.created = created;
        }

        /**
         * use in case of the index operation failed before getting to internal engine
         **/
        public IndexResult(Exception failure, long version) {
            this(failure, version, UNASSIGNED_PRIMARY_TERM, UNASSIGNED_SEQ_NO);
        }

        public IndexResult(Exception failure, long version, long term, long seqNo) {
            super(Operation.TYPE.INDEX, failure, version, term, seqNo);
            this.created = false;
        }

        public IndexResult(Mapping requiredMappingUpdate) {
            super(Operation.TYPE.INDEX, requiredMappingUpdate);
            this.created = false;
        }

        public boolean isCreated() {
            return created;
        }

    }

    /**
     * The delete result
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class DeleteResult extends Result {

        private final boolean found;

        public DeleteResult(long version, long term, long seqNo, boolean found) {
            super(Operation.TYPE.DELETE, version, term, seqNo);
            this.found = found;
        }

        /**
         * use in case of the delete operation failed before getting to internal engine
         **/
        public DeleteResult(Exception failure, long version, long term) {
            this(failure, version, term, UNASSIGNED_SEQ_NO, false);
        }

        public DeleteResult(Exception failure, long version, long term, long seqNo, boolean found) {
            super(Operation.TYPE.DELETE, failure, version, term, seqNo);
            this.found = found;
        }

        public DeleteResult(Mapping requiredMappingUpdate) {
            super(Operation.TYPE.DELETE, requiredMappingUpdate);
            this.found = false;
        }

        public boolean isFound() {
            return found;
        }

    }

    /**
     * A noop result
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class NoOpResult extends Result {

        NoOpResult(long term, long seqNo) {
            super(Operation.TYPE.NO_OP, 0, term, seqNo);
        }

        NoOpResult(long term, long seqNo, Exception failure) {
            super(Operation.TYPE.NO_OP, failure, 0, term, seqNo);
        }

    }

    protected final GetResult getFromSearcher(
        Get get,
        BiFunction<String, SearcherScope, Engine.Searcher> searcherFactory,
        SearcherScope scope
    ) throws EngineException {
        final Engine.Searcher searcher = searcherFactory.apply("get", scope);
        final DocIdAndVersion docIdAndVersion;
        try {
            docIdAndVersion = VersionsAndSeqNoResolver.loadDocIdAndVersion(searcher.getIndexReader(), get.uid(), true);
        } catch (Exception e) {
            Releasables.closeWhileHandlingException(searcher);
            // TODO: A better exception goes here
            throw new EngineException(shardId, "Couldn't resolve version", e);
        }

        if (docIdAndVersion != null) {
            if (get.versionType().isVersionConflictForReads(docIdAndVersion.version, get.version())) {
                Releasables.close(searcher);
                throw new VersionConflictEngineException(
                    shardId,
                    get.id(),
                    get.versionType().explainConflictForReads(docIdAndVersion.version, get.version())
                );
            }
            if (get.getIfSeqNo() != SequenceNumbers.UNASSIGNED_SEQ_NO
                && (get.getIfSeqNo() != docIdAndVersion.seqNo || get.getIfPrimaryTerm() != docIdAndVersion.primaryTerm)) {
                Releasables.close(searcher);
                throw new VersionConflictEngineException(
                    shardId,
                    get.id(),
                    get.getIfSeqNo(),
                    get.getIfPrimaryTerm(),
                    docIdAndVersion.seqNo,
                    docIdAndVersion.primaryTerm
                );
            }
        }

        if (docIdAndVersion != null) {
            // don't release the searcher on this path, it is the
            // responsibility of the caller to call GetResult.release
            return new GetResult(searcher, docIdAndVersion, false);
        } else {
            Releasables.close(searcher);
            return GetResult.NOT_EXISTS;
        }
    }

    public abstract GetResult get(Get get, BiFunction<String, SearcherScope, Searcher> searcherFactory) throws EngineException;

    /**
     * Acquires a point-in-time reader that can be used to create {@link Engine.Searcher}s on demand.
     */
    public final SearcherSupplier acquireSearcherSupplier(Function<Searcher, Searcher> wrapper) throws EngineException {
        return acquireSearcherSupplier(wrapper, SearcherScope.EXTERNAL);
    }

    /**
     * Acquires a point-in-time reader that can be used to create {@link Engine.Searcher}s on demand.
     */
    public SearcherSupplier acquireSearcherSupplier(Function<Searcher, Searcher> wrapper, SearcherScope scope) throws EngineException {
        /* Acquire order here is store -> manager since we need
         * to make sure that the store is not closed before
         * the searcher is acquired. */
        if (store.tryIncRef() == false) {
            throw new AlreadyClosedException(shardId + " store is closed", failedEngine.get());
        }
        Releasable releasable = store::decRef;
        try {
            ReferenceManager<OpenSearchDirectoryReader> referenceManager = getReferenceManager(scope);
            OpenSearchDirectoryReader acquire = referenceManager.acquire();
            SearcherSupplier reader = new SearcherSupplier(wrapper) {
                @Override
                public Searcher acquireSearcherInternal(String source) {
                    assert assertSearcherIsWarmedUp(source, scope);
                    return new Searcher(
                        source,
                        acquire,
                        engineConfig.getSimilarity(),
                        engineConfig.getQueryCache(),
                        engineConfig.getQueryCachingPolicy(),
                        () -> {}
                    );
                }

                @Override
                protected void doClose() {
                    try {
                        referenceManager.release(acquire);
                    } catch (IOException e) {
                        throw new UncheckedIOException("failed to close", e);
                    } catch (AlreadyClosedException e) {
                        // This means there's a bug somewhere: don't suppress it
                        throw new AssertionError(e);
                    } finally {
                        store.decRef();
                    }
                }
            };
            releasable = null; // success - hand over the reference to the engine reader
            return reader;
        } catch (AlreadyClosedException ex) {
            throw ex;
        } catch (Exception ex) {
            maybeFailEngine("acquire_reader", ex);
            ensureOpen(ex); // throw EngineCloseException here if we are already closed
            logger.error(() -> new ParameterizedMessage("failed to acquire reader"), ex);
            throw new EngineException(shardId, "failed to acquire reader", ex);
        } finally {
            Releasables.close(releasable);
        }
    }

    public final Searcher acquireSearcher(String source) throws EngineException {
        return acquireSearcher(source, SearcherScope.EXTERNAL);
    }

    public Searcher acquireSearcher(String source, SearcherScope scope) throws EngineException {
        return acquireSearcher(source, scope, Function.identity());
    }

    public Searcher acquireSearcher(String source, SearcherScope scope, Function<Searcher, Searcher> wrapper) throws EngineException {
        SearcherSupplier releasable = null;
        try {
            SearcherSupplier reader = releasable = acquireSearcherSupplier(wrapper, scope);
            Searcher searcher = reader.acquireSearcher(source);
            releasable = null;
            return new Searcher(
                source,
                searcher.getDirectoryReader(),
                searcher.getSimilarity(),
                searcher.getQueryCache(),
                searcher.getQueryCachingPolicy(),
                () -> Releasables.close(searcher, reader)
            );
        } finally {
            Releasables.close(releasable);
        }
    }

    protected abstract ReferenceManager<OpenSearchDirectoryReader> getReferenceManager(SearcherScope scope);

    boolean assertSearcherIsWarmedUp(String source, SearcherScope scope) {
        return true;
    }

    /**
     * Scope of the searcher
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public enum SearcherScope {
        EXTERNAL,
        INTERNAL
    }

    /**
     * Acquires a lock on the translog files and Lucene soft-deleted documents to prevent them from being trimmed
     */
    public abstract Closeable acquireHistoryRetentionLock();

    /**
     * Creates a new history snapshot from Lucene for reading operations whose seqno in the requesting seqno range (both inclusive).
     * This feature requires soft-deletes enabled. If soft-deletes are disabled, this method will throw an {@link IllegalStateException}.
     */
    public abstract Translog.Snapshot newChangesSnapshot(
        String source,
        long fromSeqNo,
        long toSeqNo,
        boolean requiredFullRange,
        boolean accurateCount
    ) throws IOException;

    /**
     * Counts the number of history operations in the given sequence number range
     * @param source       source of the request
     * @param fromSeqNo    from sequence number; included
     * @param toSeqNumber  to sequence number; included
     * @return             number of history operations
     */
    public abstract int countNumberOfHistoryOperations(String source, long fromSeqNo, long toSeqNumber) throws IOException;

    public abstract boolean hasCompleteOperationHistory(String reason, long startingSeqNo);

    /**
     * Gets the minimum retained sequence number for this engine.
     *
     * @return the minimum retained sequence number
     */
    public abstract long getMinRetainedSeqNo();

    protected final void ensureOpen(Exception suppressed) {
        if (isClosed.get()) {
            AlreadyClosedException ace = new AlreadyClosedException(shardId + " engine is closed", failedEngine.get());
            if (suppressed != null) {
                ace.addSuppressed(suppressed);
            }
            throw ace;
        }
    }

    public final void ensureOpen() {
        ensureOpen(null);
    }

    /** get commits stats for the last commit */
    public final CommitStats commitStats() {
        return new CommitStats(getLastCommittedSegmentInfos());
    }

    /**
     * @return the persisted local checkpoint for this Engine
     */
    public abstract long getPersistedLocalCheckpoint();

    /**
     * @return the latest checkpoint that has been processed but not necessarily persisted.
     * Also see {@link #getPersistedLocalCheckpoint()}
     */
    public abstract long getProcessedLocalCheckpoint();

    /**
     * @return a {@link SeqNoStats} object, using local state and the supplied global checkpoint
     */
    public abstract SeqNoStats getSeqNoStats(long globalCheckpoint);

    /**
     * Returns the latest global checkpoint value that has been persisted in the underlying storage (i.e. translog's checkpoint)
     */
    public abstract long getLastSyncedGlobalCheckpoint();

    /**
     * Global stats on segments.
     */
    public SegmentsStats segmentsStats(boolean includeSegmentFileSizes, boolean includeUnloadedSegments) {
        ensureOpen();
        Set<String> segmentName = new HashSet<>();
        SegmentsStats stats = new SegmentsStats();
        try (Searcher searcher = acquireSearcher("segments_stats", SearcherScope.INTERNAL)) {
            for (LeafReaderContext ctx : searcher.getIndexReader().getContext().leaves()) {
                SegmentReader segmentReader = Lucene.segmentReader(ctx.reader());
                fillSegmentStats(segmentReader, includeSegmentFileSizes, stats);
                segmentName.add(segmentReader.getSegmentName());
            }
        }

        try (Searcher searcher = acquireSearcher("segments_stats", SearcherScope.EXTERNAL)) {
            for (LeafReaderContext ctx : searcher.getIndexReader().getContext().leaves()) {
                SegmentReader segmentReader = Lucene.segmentReader(ctx.reader());
                if (segmentName.contains(segmentReader.getSegmentName()) == false) {
                    fillSegmentStats(segmentReader, includeSegmentFileSizes, stats);
                }
            }
        }
        writerSegmentStats(stats);
        return stats;
    }

    /**
     * @return Stats for pull-based ingestion.
     */
    public PollingIngestStats pollingIngestStats() {
        return null;
    }

    protected TranslogDeletionPolicy getTranslogDeletionPolicy(EngineConfig engineConfig) {
        TranslogDeletionPolicy customTranslogDeletionPolicy = null;
        if (engineConfig.getCustomTranslogDeletionPolicyFactory() != null) {
            customTranslogDeletionPolicy = engineConfig.getCustomTranslogDeletionPolicyFactory()
                .create(engineConfig.getIndexSettings(), engineConfig.retentionLeasesSupplier());
        }
        return Objects.requireNonNullElseGet(
            customTranslogDeletionPolicy,
            () -> new DefaultTranslogDeletionPolicy(
                engineConfig.getIndexSettings().getTranslogRetentionSize().getBytes(),
                engineConfig.getIndexSettings().getTranslogRetentionAge().getMillis(),
                engineConfig.getIndexSettings().getTranslogRetentionTotalFiles()
            )
        );
    }

    protected void fillSegmentStats(SegmentReader segmentReader, boolean includeSegmentFileSizes, SegmentsStats stats) {
        stats.add(1);
        if (includeSegmentFileSizes) {
            // TODO: consider moving this to StoreStats
            stats.addFileSizes(getSegmentFileSizes(segmentReader));
        }
    }

    boolean shouldCleanupUnreferencedFiles() {
        return engineConfig.getIndexSettings().shouldCleanupUnreferencedFiles();
    }

    private Map<String, Long> getSegmentFileSizes(SegmentReader segmentReader) {
        Directory directory = null;
        SegmentCommitInfo segmentCommitInfo = segmentReader.getSegmentInfo();
        boolean useCompoundFile = segmentCommitInfo.info.getUseCompoundFile();
        if (useCompoundFile) {
            try {
                directory = engineConfig.getCodec().compoundFormat().getCompoundReader(segmentReader.directory(), segmentCommitInfo.info);
            } catch (IOException e) {
                logger.warn(
                    () -> new ParameterizedMessage(
                        "Error when opening compound reader for Directory [{}] and " + "SegmentCommitInfo [{}]",
                        segmentReader.directory(),
                        segmentCommitInfo
                    ),
                    e
                );

                return Map.of();
            }
        } else {
            directory = segmentReader.directory();
        }

        assert directory != null;

        String[] files;
        if (useCompoundFile) {
            try {
                files = directory.listAll();
            } catch (IOException e) {
                final Directory finalDirectory = directory;
                logger.warn(() -> new ParameterizedMessage("Couldn't list Compound Reader Directory [{}]", finalDirectory), e);
                return Map.of();
            }
        } else {
            try {
                files = segmentReader.getSegmentInfo().files().toArray(new String[] {});
            } catch (IOException e) {
                logger.warn(
                    () -> new ParameterizedMessage(
                        "Couldn't list Directory from SegmentReader [{}] and SegmentInfo [{}]",
                        segmentReader,
                        segmentReader.getSegmentInfo()
                    ),
                    e
                );
                return Map.of();
            }
        }

        Map<String, Long> map = new HashMap<>();
        for (String file : files) {
            String extension = IndexFileNames.getExtension(file);
            long length = 0L;
            try {
                length = directory.fileLength(file);
            } catch (NoSuchFileException | FileNotFoundException e) {
                final Directory finalDirectory = directory;
                logger.warn(
                    () -> new ParameterizedMessage("Tried to query fileLength but file is gone [{}] [{}]", finalDirectory, file),
                    e
                );
            } catch (IOException e) {
                final Directory finalDirectory = directory;
                logger.warn(() -> new ParameterizedMessage("Error when trying to query fileLength [{}] [{}]", finalDirectory, file), e);
            }
            if (length == 0L) {
                continue;
            }
            map.put(extension, length);
        }

        if (useCompoundFile) {
            try {
                directory.close();
            } catch (IOException e) {
                final Directory finalDirectory = directory;
                logger.warn(() -> new ParameterizedMessage("Error when closing compound reader on Directory [{}]", finalDirectory), e);
            }
        }

        return Collections.unmodifiableMap(map);
    }

    protected void writerSegmentStats(SegmentsStats stats) {
        // by default we don't have a writer here... subclasses can override this
        stats.addVersionMapMemoryInBytes(0);
        stats.addIndexWriterMemoryInBytes(0);
    }

    /** How much heap is used that would be freed by a refresh.  Note that this may throw {@link AlreadyClosedException}. */
    public abstract long getIndexBufferRAMBytesUsed();

    final Segment[] getSegmentInfo(SegmentInfos lastCommittedSegmentInfos, boolean verbose) {
        ensureOpen();
        Map<String, Segment> segments = new HashMap<>();
        // first, go over and compute the search ones...
        try (Searcher searcher = acquireSearcher("segments", SearcherScope.EXTERNAL)) {
            for (LeafReaderContext ctx : searcher.getIndexReader().getContext().leaves()) {
                fillSegmentInfo(Lucene.segmentReader(ctx.reader()), verbose, true, segments);
            }
        }

        try (Searcher searcher = acquireSearcher("segments", SearcherScope.INTERNAL)) {
            for (LeafReaderContext ctx : searcher.getIndexReader().getContext().leaves()) {
                SegmentReader segmentReader = Lucene.segmentReader(ctx.reader());
                if (segments.containsKey(segmentReader.getSegmentName()) == false) {
                    fillSegmentInfo(segmentReader, verbose, false, segments);
                }
            }
        }

        // now, correlate or add the committed ones...
        if (lastCommittedSegmentInfos != null) {
            for (SegmentCommitInfo info : lastCommittedSegmentInfos) {
                Segment segment = segments.get(info.info.name);
                if (segment == null) {
                    segment = new Segment(info.info.name);
                    segment.search = false;
                    segment.committed = true;
                    segment.delDocCount = info.getDelCount() + info.getSoftDelCount();
                    segment.docCount = info.info.maxDoc() - segment.delDocCount;
                    segment.version = info.info.getVersion();
                    segment.compound = info.info.getUseCompoundFile();
                    try {
                        segment.sizeInBytes = info.sizeInBytes();
                    } catch (IOException e) {
                        logger.trace(() -> new ParameterizedMessage("failed to get size for [{}]", info.info.name), e);
                    }
                    segment.segmentSort = info.info.getIndexSort();
                    segment.attributes = info.info.getAttributes();
                    segments.put(info.info.name, segment);
                } else {
                    segment.committed = true;
                }
            }
        }

        Segment[] segmentsArr = segments.values().toArray(new Segment[0]);
        Arrays.sort(segmentsArr, Comparator.comparingLong(Segment::getGeneration));
        return segmentsArr;
    }

    private void fillSegmentInfo(SegmentReader segmentReader, boolean verbose, boolean search, Map<String, Segment> segments) {
        SegmentCommitInfo info = segmentReader.getSegmentInfo();
        assert segments.containsKey(info.info.name) == false;
        Segment segment = new Segment(info.info.name);
        segment.search = search;
        segment.docCount = segmentReader.numDocs();
        segment.delDocCount = segmentReader.numDeletedDocs();
        segment.version = info.info.getVersion();
        segment.compound = info.info.getUseCompoundFile();
        try {
            segment.sizeInBytes = info.sizeInBytes();
        } catch (IOException e) {
            logger.trace(() -> new ParameterizedMessage("failed to get size for [{}]", info.info.name), e);
        }
        segment.segmentSort = info.info.getIndexSort();
        segment.attributes = info.info.getAttributes();
        // TODO: add more fine grained mem stats values to per segment info here
        segments.put(info.info.name, segment);
    }

    /**
     * The list of segments in the engine.
     */
    public abstract List<Segment> segments(boolean verbose);

    public boolean refreshNeeded() {
        if (store.tryIncRef()) {
            /*
              we need to inc the store here since we acquire a searcher and that might keep a file open on the
              store. this violates the assumption that all files are closed when
              the store is closed so we need to make sure we increment it here
             */
            try {
                try (Searcher searcher = acquireSearcher("refresh_needed", SearcherScope.EXTERNAL)) {
                    return searcher.getDirectoryReader().isCurrent() == false;
                }
            } catch (IOException e) {
                logger.error("failed to access searcher manager", e);
                failEngine("failed to access searcher manager", e);
                throw new EngineException(shardId, "failed to access searcher manager", e);
            } finally {
                store.decRef();
            }
        }
        return false;
    }

    /**
     * Synchronously refreshes the engine for new search operations to reflect the latest
     * changes.
     */
    @Nullable
    public abstract void refresh(String source) throws EngineException;

    /**
     * Synchronously refreshes the engine for new search operations to reflect the latest
     * changes unless another thread is already refreshing the engine concurrently.
     *
     * @return <code>true</code> if the a refresh happened. Otherwise <code>false</code>
     */
    @Nullable
    public abstract boolean maybeRefresh(String source) throws EngineException;

    /**
     * Called when our engine is using too much heap and should move buffered indexed/deleted documents to disk.
     */
    // NOTE: do NOT rename this to something containing flush or refresh!
    public abstract void writeIndexingBuffer() throws EngineException;

    /**
     * Checks if this engine should be flushed periodically.
     * This check is mainly based on the uncommitted translog size and the translog flush threshold setting.
     */
    public abstract boolean shouldPeriodicallyFlush();

    /**
     * Flushes the state of the engine including the transaction log, clearing memory.
     *
     * @param force         if <code>true</code> a lucene commit is executed even if no changes need to be committed.
     * @param waitIfOngoing if <code>true</code> this call will block until all currently running flushes have finished.
     *                      Otherwise this call will return without blocking.
     */
    public abstract void flush(boolean force, boolean waitIfOngoing) throws EngineException;

    /**
     * Flushes the state of the engine including the transaction log, clearing memory and persisting
     * documents in the lucene index to disk including a potentially heavy and durable fsync operation.
     * This operation is not going to block if another flush operation is currently running and won't write
     * a lucene commit if nothing needs to be committed.
     */
    public final void flush() throws EngineException {
        flush(false, false);
    }

    /**
     * Triggers a forced merge on this engine
     */
    public abstract void forceMerge(
        boolean flush,
        int maxNumSegments,
        boolean onlyExpungeDeletes,
        boolean upgrade,
        boolean upgradeOnlyAncientSegments,
        String forceMergeUUID
    ) throws EngineException, IOException;

    /**
     * Snapshots the most recent index and returns a handle to it. If needed will try and "commit" the
     * lucene index to make sure we have a "fresh" copy of the files to snapshot.
     *
     * @param flushFirst indicates whether the engine should flush before returning the snapshot
     */
    public abstract GatedCloseable<IndexCommit> acquireLastIndexCommit(boolean flushFirst) throws EngineException;

    /**
     * Snapshots the most recent safe index commit from the engine.
     */
    public abstract GatedCloseable<IndexCommit> acquireSafeIndexCommit() throws EngineException;

    /**
     * @return a summary of the contents of the current safe commit
     */
    public abstract SafeCommitInfo getSafeCommitInfo();

    /**
     * If the specified throwable contains a fatal error in the throwable graph, such a fatal error will be thrown. Callers should ensure
     * that there are no catch statements that would catch an error in the stack as the fatal error here should go uncaught and be handled
     * by the uncaught exception handler that we install during bootstrap. If the specified throwable does indeed contain a fatal error,
     * the specified message will attempt to be logged before throwing the fatal error. If the specified throwable does not contain a fatal
     * error, this method is a no-op.
     *
     * @param maybeMessage the message to maybe log
     * @param maybeFatal   the throwable that maybe contains a fatal error
     */
    @SuppressWarnings("finally")
    private void maybeDie(final String maybeMessage, final Throwable maybeFatal) {
        ExceptionsHelper.maybeError(maybeFatal).ifPresent(error -> {
            try {
                logger.error(maybeMessage, error);
            } finally {
                throw error;
            }
        });
    }

    /**
     * fail engine due to some error. the engine will also be closed.
     * The underlying store is marked corrupted iff failure is caused by index corruption
     */
    public void failEngine(String reason, @Nullable Exception failure) {
        if (failure != null) {
            maybeDie(reason, failure);
        }
        if (failEngineLock.tryLock()) {
            try {
                if (failedEngine.get() != null) {
                    logger.warn(
                        () -> new ParameterizedMessage("tried to fail engine but engine is already failed. ignoring. [{}]", reason),
                        failure
                    );
                    return;
                }
                // this must happen before we close IW or Translog such that we can check this state to opt out of failing the engine
                // again on any caught AlreadyClosedException
                failedEngine.set((failure != null) ? failure : new IllegalStateException(reason));
                try {
                    // we just go and close this engine - no way to recover
                    closeNoLock("engine failed on: [" + reason + "]", closedLatch);
                } finally {
                    logger.warn(() -> new ParameterizedMessage("failed engine [{}]", reason), failure);
                    // we must set a failure exception, generate one if not supplied
                    // we first mark the store as corrupted before we notify any listeners
                    // this must happen first otherwise we might try to reallocate so quickly
                    // on the same node that we don't see the corrupted marker file when
                    // the shard is initializing
                    if (Lucene.isCorruptionException(failure)) {
                        if (store.tryIncRef()) {
                            try {
                                store.markStoreCorrupted(
                                    new IOException("failed engine (reason: [" + reason + "])", ExceptionsHelper.unwrapCorruption(failure))
                                );
                            } catch (IOException e) {
                                logger.warn("Couldn't mark store corrupted", e);
                            } finally {
                                store.decRef();
                            }
                        } else {
                            logger.warn(
                                () -> new ParameterizedMessage(
                                    "tried to mark store as corrupted but store is already closed. [{}]",
                                    reason
                                ),
                                failure
                            );
                        }
                    }

                    // If cleanup of unreferenced flag is enabled and force merge or regular merge failed due to IOException,
                    // clean all unreferenced files on best effort basis created during failed merge and reset the
                    // shard state back to last Lucene Commit.
                    if (shouldCleanupUnreferencedFiles() && isMergeFailureDueToIOException(failure, reason)) {
                        logger.info("Cleaning up unreferenced files as merge failed due to: {}", reason);
                        cleanUpUnreferencedFiles();
                    }

                    eventListener.onFailedEngine(reason, failure);
                }
            } catch (Exception inner) {
                if (failure != null) inner.addSuppressed(failure);
                // don't bubble up these exceptions up
                logger.warn("failEngine threw exception", inner);
            }
        } else {
            logger.debug(
                () -> new ParameterizedMessage(
                    "tried to fail engine but could not acquire lock - engine should " + "be failed by now [{}]",
                    reason
                ),
                failure
            );
        }
    }

    /**
     * Cleanup all unreferenced files generated during failed segment merge. This resets shard state to last Lucene
     * commit.
     */
    private void cleanUpUnreferencedFiles() {
        try (
            IndexWriter writer = new IndexWriter(
                store.directory(),
                new IndexWriterConfig(Lucene.STANDARD_ANALYZER).setSoftDeletesField(Lucene.SOFT_DELETES_FIELD)
                    .setCommitOnClose(false)
                    .setMergePolicy(NoMergePolicy.INSTANCE)
                    .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
            )
        ) {
            // do nothing except increasing metric count and close this will kick off IndexFileDeleter which will
            // remove all unreferenced files
            totalUnreferencedFileCleanUpsPerformed.inc();
        } catch (Exception ex) {
            logger.error("Error while deleting unreferenced file ", ex);
        }
    }

    /** Check whether the merge failure happened due to IOException. */
    private boolean isMergeFailureDueToIOException(Exception failure, String reason) {
        return (reason.equals(FORCE_MERGE) || reason.equals(MERGE_FAILED))
            && ExceptionsHelper.unwrap(failure, IOException.class) instanceof IOException;
    }

    /** Check whether the engine should be failed */
    protected boolean maybeFailEngine(String source, Exception e) {
        if (Lucene.isCorruptionException(e)) {
            failEngine("corrupt file (source: [" + source + "])", e);
            return true;
        }
        return false;
    }

    /**
     * Event listener for the engine
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public interface EventListener {
        /**
         * Called when a fatal exception occurred
         */
        default void onFailedEngine(String reason, @Nullable Exception e) {}
    }

    /**
     * Supplier for the searcher
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public abstract static class SearcherSupplier implements Releasable {
        private final Function<Searcher, Searcher> wrapper;
        private final AtomicBoolean released = new AtomicBoolean(false);

        public SearcherSupplier(Function<Searcher, Searcher> wrapper) {
            this.wrapper = wrapper;
        }

        public final Searcher acquireSearcher(String source) {
            if (released.get()) {
                throw new AlreadyClosedException("SearcherSupplier was closed");
            }
            final Searcher searcher = acquireSearcherInternal(source);
            return CAN_MATCH_SEARCH_SOURCE.equals(source) ? searcher : wrapper.apply(searcher);
        }

        @Override
        public final void close() {
            if (released.compareAndSet(false, true)) {
                doClose();
            } else {
                assert false : "SearchSupplier was released twice";
            }
        }

        protected abstract void doClose();

        protected abstract Searcher acquireSearcherInternal(String source);
    }

    /**
     * The engine searcher
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static final class Searcher extends IndexSearcher implements Releasable {
        private final String source;
        private final Closeable onClose;

        public Searcher(
            String source,
            IndexReader reader,
            Similarity similarity,
            QueryCache queryCache,
            QueryCachingPolicy queryCachingPolicy,
            Closeable onClose
        ) {
            super(reader);
            setSimilarity(similarity);
            setQueryCache(queryCache);
            setQueryCachingPolicy(queryCachingPolicy);
            this.source = source;
            this.onClose = onClose;
        }

        /**
         * The source that caused this searcher to be acquired.
         */
        public String source() {
            return source;
        }

        public DirectoryReader getDirectoryReader() {
            if (getIndexReader() instanceof DirectoryReader) {
                return (DirectoryReader) getIndexReader();
            }
            throw new IllegalStateException("Can't use " + getIndexReader().getClass() + " as a directory reader");
        }

        @Override
        public void close() {
            try {
                onClose.close();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to close", e);
            } catch (AlreadyClosedException e) {
                // This means there's a bug somewhere: don't suppress it
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Base operation class
     *
     * @opensearch.internal
     */
    public abstract static class Operation {

        /**
         * type of operation (index, delete), subclasses use static types
         *
         * @opensearch.api
         */
        @PublicApi(since = "1.0.0")
        public enum TYPE {
            INDEX,
            DELETE,
            NO_OP;

            private final String lowercase;

            TYPE() {
                this.lowercase = this.toString().toLowerCase(Locale.ROOT);
            }

            public String getLowercase() {
                return lowercase;
            }
        }

        private final Term uid;
        private final long version;
        private final long seqNo;
        private final long primaryTerm;
        private final VersionType versionType;
        private final Origin origin;
        private final long startTime;

        public Operation(Term uid, long seqNo, long primaryTerm, long version, VersionType versionType, Origin origin, long startTime) {
            this.uid = uid;
            this.seqNo = seqNo;
            this.primaryTerm = primaryTerm;
            this.version = version;
            this.versionType = versionType;
            this.origin = origin;
            this.startTime = startTime;
        }

        /**
         * Origin of the operation
         *
         * @opensearch.api
         */
        @PublicApi(since = "1.0.0")
        public enum Origin {
            PRIMARY,
            REPLICA,
            PEER_RECOVERY,
            LOCAL_TRANSLOG_RECOVERY,
            LOCAL_RESET;

            public boolean isRecovery() {
                return this == PEER_RECOVERY || this == LOCAL_TRANSLOG_RECOVERY;
            }

            boolean isFromTranslog() {
                return this == LOCAL_TRANSLOG_RECOVERY || this == LOCAL_RESET;
            }
        }

        public Origin origin() {
            return this.origin;
        }

        public Term uid() {
            return this.uid;
        }

        public long version() {
            return this.version;
        }

        public long seqNo() {
            return seqNo;
        }

        public long primaryTerm() {
            return primaryTerm;
        }

        public abstract int estimatedSizeInBytes();

        public VersionType versionType() {
            return this.versionType;
        }

        /**
         * Returns operation start time in nanoseconds.
         */
        public long startTime() {
            return this.startTime;
        }

        abstract String id();

        public abstract TYPE operationType();
    }

    /**
     * Index operation
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class Index extends Operation {

        private final ParsedDocument doc;
        private final long autoGeneratedIdTimestamp;
        private final boolean isRetry;
        private final long ifSeqNo;
        private final long ifPrimaryTerm;

        public Index(
            Term uid,
            ParsedDocument doc,
            long seqNo,
            long primaryTerm,
            long version,
            VersionType versionType,
            Origin origin,
            long startTime,
            long autoGeneratedIdTimestamp,
            boolean isRetry,
            long ifSeqNo,
            long ifPrimaryTerm
        ) {
            super(uid, seqNo, primaryTerm, version, versionType, origin, startTime);
            assert (origin == Origin.PRIMARY) == (versionType != null) : "invalid version_type=" + versionType + " for origin=" + origin;
            assert ifPrimaryTerm >= 0 : "ifPrimaryTerm [" + ifPrimaryTerm + "] must be non negative";
            assert ifSeqNo == UNASSIGNED_SEQ_NO || ifSeqNo >= 0 : "ifSeqNo [" + ifSeqNo + "] must be non negative or unset";
            assert (origin == Origin.PRIMARY) || (ifSeqNo == UNASSIGNED_SEQ_NO && ifPrimaryTerm == UNASSIGNED_PRIMARY_TERM)
                : "cas operations are only allowed if origin is primary. get [" + origin + "]";
            this.doc = doc;
            this.isRetry = isRetry;
            this.autoGeneratedIdTimestamp = autoGeneratedIdTimestamp;
            this.ifSeqNo = ifSeqNo;
            this.ifPrimaryTerm = ifPrimaryTerm;
        }

        public Index(Term uid, long primaryTerm, ParsedDocument doc) {
            this(uid, primaryTerm, doc, Versions.MATCH_ANY);
        } // TEST ONLY

        Index(Term uid, long primaryTerm, ParsedDocument doc, long version) {
            this(
                uid,
                doc,
                UNASSIGNED_SEQ_NO,
                primaryTerm,
                version,
                VersionType.INTERNAL,
                Origin.PRIMARY,
                System.nanoTime(),
                -1,
                false,
                UNASSIGNED_SEQ_NO,
                0
            );
        } // TEST ONLY

        public ParsedDocument parsedDoc() {
            return this.doc;
        }

        @Override
        public String id() {
            return this.doc.id();
        }

        @Override
        public TYPE operationType() {
            return TYPE.INDEX;
        }

        public String routing() {
            return this.doc.routing();
        }

        public List<Document> docs() {
            return this.doc.docs();
        }

        public BytesReference source() {
            return this.doc.source();
        }

        @Override
        public int estimatedSizeInBytes() {
            return id().length() * 2 + source().length() + 12;
        }

        /**
         * Returns a positive timestamp if the ID of this document is auto-generated by opensearch.
         * if this property is non-negative indexing code might optimize the addition of this document
         * due to it's append only nature.
         */
        public long getAutoGeneratedIdTimestamp() {
            return autoGeneratedIdTimestamp;
        }

        /**
         * Returns <code>true</code> if this index requests has been retried on the coordinating node and can therefor be delivered
         * multiple times. Note: this might also be set to true if an equivalent event occurred like the replay of the transaction log
         */
        public boolean isRetry() {
            return isRetry;
        }

        public long getIfSeqNo() {
            return ifSeqNo;
        }

        public long getIfPrimaryTerm() {
            return ifPrimaryTerm;
        }
    }

    /**
     * Delete operation
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class Delete extends Operation {

        private final String id;
        private final long ifSeqNo;
        private final long ifPrimaryTerm;

        public Delete(
            String id,
            Term uid,
            long seqNo,
            long primaryTerm,
            long version,
            VersionType versionType,
            Origin origin,
            long startTime,
            long ifSeqNo,
            long ifPrimaryTerm
        ) {
            super(uid, seqNo, primaryTerm, version, versionType, origin, startTime);
            assert (origin == Origin.PRIMARY) == (versionType != null) : "invalid version_type=" + versionType + " for origin=" + origin;
            assert ifPrimaryTerm >= 0 : "ifPrimaryTerm [" + ifPrimaryTerm + "] must be non negative";
            assert ifSeqNo == UNASSIGNED_SEQ_NO || ifSeqNo >= 0 : "ifSeqNo [" + ifSeqNo + "] must be non negative or unset";
            assert (origin == Origin.PRIMARY) || (ifSeqNo == UNASSIGNED_SEQ_NO && ifPrimaryTerm == UNASSIGNED_PRIMARY_TERM)
                : "cas operations are only allowed if origin is primary. get [" + origin + "]";
            this.id = Objects.requireNonNull(id);
            this.ifSeqNo = ifSeqNo;
            this.ifPrimaryTerm = ifPrimaryTerm;
        }

        public Delete(String id, Term uid, long primaryTerm) {
            this(
                id,
                uid,
                UNASSIGNED_SEQ_NO,
                primaryTerm,
                Versions.MATCH_ANY,
                VersionType.INTERNAL,
                Origin.PRIMARY,
                System.nanoTime(),
                UNASSIGNED_SEQ_NO,
                0
            );
        }

        public Delete(Delete template, VersionType versionType) {
            this(
                template.id(),
                template.uid(),
                template.seqNo(),
                template.primaryTerm(),
                template.version(),
                versionType,
                template.origin(),
                template.startTime(),
                UNASSIGNED_SEQ_NO,
                0
            );
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public TYPE operationType() {
            return TYPE.DELETE;
        }

        @Override
        public int estimatedSizeInBytes() {
            return (uid().field().length() + uid().text().length()) * 2 + 20;
        }

        public long getIfSeqNo() {
            return ifSeqNo;
        }

        public long getIfPrimaryTerm() {
            return ifPrimaryTerm;
        }
    }

    /**
     * noop operation
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class NoOp extends Operation {

        private final String reason;

        public String reason() {
            return reason;
        }

        public NoOp(final long seqNo, final long primaryTerm, final Origin origin, final long startTime, final String reason) {
            super(null, seqNo, primaryTerm, Versions.NOT_FOUND, null, origin, startTime);
            this.reason = reason;
        }

        @Override
        public Term uid() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long version() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VersionType versionType() {
            throw new UnsupportedOperationException();
        }

        @Override
        String id() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TYPE operationType() {
            return TYPE.NO_OP;
        }

        @Override
        public int estimatedSizeInBytes() {
            return 2 * reason.length() + 2 * Long.BYTES;
        }

    }

    /**
     * Get operation
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class Get {
        private final boolean realtime;
        private final Term uid;
        private final String id;
        private final boolean readFromTranslog;
        private long version = Versions.MATCH_ANY;
        private VersionType versionType = VersionType.INTERNAL;
        private long ifSeqNo = UNASSIGNED_SEQ_NO;
        private long ifPrimaryTerm = UNASSIGNED_PRIMARY_TERM;

        public Get(boolean realtime, boolean readFromTranslog, String id, Term uid) {
            this.realtime = realtime;
            this.id = id;
            this.uid = uid;
            this.readFromTranslog = readFromTranslog;
        }

        public boolean realtime() {
            return this.realtime;
        }

        public String id() {
            return id;
        }

        public Term uid() {
            return uid;
        }

        public long version() {
            return version;
        }

        public Get version(long version) {
            this.version = version;
            return this;
        }

        public VersionType versionType() {
            return versionType;
        }

        public Get versionType(VersionType versionType) {
            this.versionType = versionType;
            return this;
        }

        public boolean isReadFromTranslog() {
            return readFromTranslog;
        }

        public Get setIfSeqNo(long seqNo) {
            this.ifSeqNo = seqNo;
            return this;
        }

        public long getIfSeqNo() {
            return ifSeqNo;
        }

        public Get setIfPrimaryTerm(long primaryTerm) {
            this.ifPrimaryTerm = primaryTerm;
            return this;
        }

        public long getIfPrimaryTerm() {
            return ifPrimaryTerm;
        }

    }

    /**
     * The Get result
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class GetResult implements Releasable {
        private final boolean exists;
        private final long version;
        private final DocIdAndVersion docIdAndVersion;
        private final Engine.Searcher searcher;
        private final boolean fromTranslog;

        public static final GetResult NOT_EXISTS = new GetResult(false, Versions.NOT_FOUND, null, null, false);

        private GetResult(boolean exists, long version, DocIdAndVersion docIdAndVersion, Engine.Searcher searcher, boolean fromTranslog) {
            this.exists = exists;
            this.version = version;
            this.docIdAndVersion = docIdAndVersion;
            this.searcher = searcher;
            this.fromTranslog = fromTranslog;
        }

        public GetResult(Engine.Searcher searcher, DocIdAndVersion docIdAndVersion, boolean fromTranslog) {
            this(true, docIdAndVersion.version, docIdAndVersion, searcher, fromTranslog);
        }

        public boolean exists() {
            return exists;
        }

        public long version() {
            return this.version;
        }

        public boolean isFromTranslog() {
            return fromTranslog;
        }

        public Engine.Searcher searcher() {
            return this.searcher;
        }

        public DocIdAndVersion docIdAndVersion() {
            return docIdAndVersion;
        }

        @Override
        public void close() {
            Releasables.close(searcher);
        }
    }

    /**
     * Method to close the engine while the write lock is held.
     * Must decrement the supplied when closing work is done and resources are
     * freed.
     */
    protected abstract void closeNoLock(String reason, CountDownLatch closedLatch);

    /**
     * Flush the engine (committing segments to disk and truncating the
     * translog) and close it.
     */
    public void flushAndClose() throws IOException {
        if (isClosed.get() == false) {
            logger.trace("flushAndClose now acquire writeLock");
            try (ReleasableLock lock = writeLock.acquire()) {
                logger.trace("flushAndClose now acquired writeLock");
                try {
                    logger.debug("flushing shard on close - this might take some time to sync files to disk");
                    try {
                        // TODO we might force a flush in the future since we have the write lock already even though recoveries
                        // are running.
                        flush();
                    } catch (AlreadyClosedException ex) {
                        logger.debug("engine already closed - skipping flushAndClose");
                    }
                } finally {
                    close(); // double close is not a problem
                }
            }
        }
        awaitPendingClose();
    }

    @Override
    public void close() throws IOException {
        if (isClosed.get() == false) { // don't acquire the write lock if we are already closed
            logger.debug("close now acquiring writeLock");
            try (ReleasableLock lock = writeLock.acquire()) {
                logger.debug("close acquired writeLock");
                closeNoLock("api", closedLatch);
            }
        }
        awaitPendingClose();
    }

    private void awaitPendingClose() {
        try {
            closedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void onSettingsChanged(TimeValue translogRetentionAge, ByteSizeValue translogRetentionSize, long softDeletesRetentionOps) {

    }

    /**
     * Returns the timestamp of the last write in nanoseconds.
     * Note: this time might not be absolutely accurate since the {@link Operation#startTime()} is used which might be
     * slightly inaccurate.
     *
     * @see System#nanoTime()
     * @see Operation#startTime()
     */
    public long getLastWriteNanos() {
        return this.lastWriteNanos;
    }

    /**
     * Called for each new opened engine reader to warm new segments
     *
     * @see EngineConfig#getWarmer()
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public interface Warmer {
        /**
         * Called once a new top-level reader is opened.
         */
        void warm(OpenSearchDirectoryReader reader);
    }

    /**
     * Request that this engine throttle incoming indexing requests to one thread.
     * Must be matched by a later call to {@link #deactivateThrottling()}.
     */
    public abstract void activateThrottling();

    /**
     * Reverses a previous {@link #activateThrottling} call.
     */
    public abstract void deactivateThrottling();

    /**
     * Fills up the local checkpoints history with no-ops until the local checkpoint
     * and the max seen sequence ID are identical.
     * @param primaryTerm the shards primary term this engine was created for
     * @return the number of no-ops added
     */
    public abstract int fillSeqNoGaps(long primaryTerm) throws IOException;

    /**
     * Tries to prune buffered deletes from the version map.
     */
    public abstract void maybePruneDeletes();

    /**
     * Returns the maximum auto_id_timestamp of all append-only index requests have been processed by this engine
     * or the auto_id_timestamp received from its primary shard via {@link #updateMaxUnsafeAutoIdTimestamp(long)}.
     * Notes this method returns the auto_id_timestamp of all append-only requests, not max_unsafe_auto_id_timestamp.
     */
    public long getMaxSeenAutoIdTimestamp() {
        return IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP;
    }

    /**
     * Forces this engine to advance its max_unsafe_auto_id_timestamp marker to at least the given timestamp.
     * The engine will disable optimization for all append-only whose timestamp at most {@code newTimestamp}.
     */
    public abstract void updateMaxUnsafeAutoIdTimestamp(long newTimestamp);

    /**
     * Returns the maximum sequence number of either update or delete operations have been processed in this engine
     * or the sequence number from {@link #advanceMaxSeqNoOfUpdatesOrDeletes(long)}. An index request is considered
     * as an update operation if it overwrites the existing documents in Lucene index with the same document id.
     * <p>
     * A note on the optimization using max_seq_no_of_updates_or_deletes:
     * For each operation O, the key invariants are:
     * <ol>
     *     <li> I1: There is no operation on docID(O) with seqno that is {@literal > MSU(O) and < seqno(O)} </li>
     *     <li> I2: If {@literal MSU(O) < seqno(O)} then docID(O) did not exist when O was applied; more precisely, if there is any O'
     *              with {@literal seqno(O') < seqno(O) and docID(O') = docID(O)} then the one with the greatest seqno is a delete.</li>
     * </ol>
     * <p>
     * When a receiving shard (either a replica or a follower) receives an operation O, it must first ensure its own MSU at least MSU(O),
     * and then compares its MSU to its local checkpoint (LCP). If {@literal LCP < MSU} then there's a gap: there may be some operations
     * that act on docID(O) about which we do not yet know, so we cannot perform an add. Note this also covers the case where a future
     * operation O' with {@literal seqNo(O') > seqNo(O) and docId(O') = docID(O)} is processed before O. In that case MSU(O') is at least
     * seqno(O') and this means {@literal MSU >= seqNo(O') > seqNo(O) > LCP} (because O wasn't processed yet).
     * <p>
     * However, if {@literal MSU <= LCP} then there is no gap: we have processed every {@literal operation <= LCP}, and no operation O'
     * with {@literal seqno(O') > LCP and seqno(O') < seqno(O) also has docID(O') = docID(O)}, because such an operation would have
     * {@literal seqno(O') > LCP >= MSU >= MSU(O)} which contradicts the first invariant. Furthermore in this case we immediately know
     * that docID(O) has been deleted (or never existed) without needing to check Lucene for the following reason. If there's no earlier
     * operation on docID(O) then this is clear, so suppose instead that the preceding operation on docID(O) is O':
     * 1. The first invariant above tells us that {@literal seqno(O') <= MSU(O) <= LCP} so we have already applied O' to Lucene.
     * 2. Also {@literal MSU(O) <= MSU <= LCP < seqno(O)} (we discard O if {@literal seqno(O) <= LCP}) so the second invariant applies,
     *    meaning that the O' was a delete.
     * <p>
     * Therefore, if {@literal MSU <= LCP < seqno(O)} we know that O can safely be optimized with and added to lucene with addDocument.
     * Moreover, operations that are optimized using the MSU optimization must not be processed twice as this will create duplicates
     * in Lucene. To avoid this we check the local checkpoint tracker to see if an operation was already processed.
     *
     * @see #advanceMaxSeqNoOfUpdatesOrDeletes(long)
     */
    public abstract long getMaxSeqNoOfUpdatesOrDeletes();

    /**
     * A replica shard receives a new max_seq_no_of_updates from its primary shard, then calls this method
     * to advance this marker to at least the given sequence number.
     */
    public abstract void advanceMaxSeqNoOfUpdatesOrDeletes(long maxSeqNoOfUpdatesOnPrimary);

}
