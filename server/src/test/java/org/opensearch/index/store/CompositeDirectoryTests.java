/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.opensearch.core.common.breaker.CircuitBreaker;
import org.opensearch.core.common.breaker.NoopCircuitBreaker;
import org.opensearch.index.store.remote.file.CleanerDaemonThreadLeakFilter;
import org.opensearch.index.store.remote.file.OnDemandBlockSnapshotIndexInput;
import org.opensearch.index.store.remote.filecache.CachedIndexInput;
import org.opensearch.index.store.remote.filecache.FileCache;
import org.opensearch.index.store.remote.filecache.FileCache.RestoredCachedIndexInput;
import org.opensearch.index.store.remote.filecache.FileCacheFactory;
import org.opensearch.index.store.remote.filecache.FileCachedIndexInput;
import org.opensearch.index.store.remote.utils.FileTypeUtils;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

@ThreadLeakFilters(filters = CleanerDaemonThreadLeakFilter.class)
public class CompositeDirectoryTests extends BaseRemoteSegmentStoreDirectoryTests {
    private FileCache fileCache;
    private FSDirectory localDirectory;
    private CompositeDirectory compositeDirectory;

    private final static String[] LOCAL_FILES = new String[] {
        "_1.cfe",
        "_1.cfe_block_0",
        "_1.cfe_block_1",
        "_2.cfe",
        "_0.cfe_block_7",
        "_0.cfs_block_7",
        "temp_file.tmp" };
    private final static String FILE_PRESENT_LOCALLY = "_1.cfe";
    private final static String BLOCK_FILE_PRESENT_LOCALLY = "_1.cfe_block_0";
    private final static String FILE_PRESENT_IN_REMOTE_ONLY = "_0.si";
    private final static String NON_EXISTENT_FILE = "non_existent_file";
    private final static String NEW_FILE = "new_file";
    private final static String TEMP_FILE = "temp_file.tmp";
    private final static String LOCAL_SEGMENT_FILE = "segments_2";
    private final static int FILE_CACHE_CAPACITY = 10000;

    @Before
    public void setup() throws IOException {
        setupRemoteSegmentStoreDirectory();
        populateMetadata();
        remoteSegmentStoreDirectory.init();
        localDirectory = FSDirectory.open(createTempDir());
        removeExtraFSFiles();
        fileCache = FileCacheFactory.createConcurrentLRUFileCache(FILE_CACHE_CAPACITY, new NoopCircuitBreaker(CircuitBreaker.REQUEST));
        compositeDirectory = new CompositeDirectory(localDirectory, remoteSegmentStoreDirectory, fileCache, threadPool);
        addFilesToDirectory(LOCAL_FILES);
    }

    public void testListAll() throws IOException {
        String[] actualFileNames = compositeDirectory.listAll();
        String[] expectedFileNames = new String[] { "_0.cfe", "_0.cfs", "_0.si", "_1.cfe", "_2.cfe", "segments_1", "temp_file.tmp" };
        assertArrayEquals(expectedFileNames, actualFileNames);
    }

    public void testListAll_withLocalSegmentFiles() throws IOException {
        addFilesToDirectory(new String[] { LOCAL_SEGMENT_FILE });
        String[] actualFileNames = compositeDirectory.listAll();
        String[] expectedFileNames = new String[] { "_0.cfe", "_0.cfs", "_0.si", "_1.cfe", "_2.cfe", "segments_2", "temp_file.tmp" };
        assertArrayEquals(expectedFileNames, actualFileNames);
    }

    public void testDeleteFile() throws IOException {
        assertTrue(existsInCompositeDirectory(FILE_PRESENT_LOCALLY));
        assertTrue(existsInLocalDirectory(BLOCK_FILE_PRESENT_LOCALLY));
        // Delete the file and assert that it no more is a part of the directory
        compositeDirectory.deleteFile(FILE_PRESENT_LOCALLY);
        assertFalse(existsInCompositeDirectory(FILE_PRESENT_LOCALLY));
        assertFalse(existsInCompositeDirectory(BLOCK_FILE_PRESENT_LOCALLY));
        // Deletion of non-existent file should fail silently without throwing any error
        Exception exception = null;
        try {
            compositeDirectory.deleteFile(NON_EXISTENT_FILE);
        } catch (Exception e) {
            exception = e;
        }
        assertNull(exception);
    }

    public void testFileLength() throws IOException {
        // File present locally
        assertTrue(existsInLocalDirectory(FILE_PRESENT_LOCALLY));
        assertFalse(existsInRemoteDirectory(FILE_PRESENT_LOCALLY));
        assertEquals(compositeDirectory.fileLength(FILE_PRESENT_LOCALLY), localDirectory.fileLength(FILE_PRESENT_LOCALLY));

        // File not present locally - present in Remote
        assertFalse(existsInLocalDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        assertTrue(existsInRemoteDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        assertEquals(
            compositeDirectory.fileLength(FILE_PRESENT_IN_REMOTE_ONLY),
            remoteSegmentStoreDirectory.fileLength(FILE_PRESENT_IN_REMOTE_ONLY)
        );

        // File not present in both local and remote
        assertFalse(Arrays.asList(compositeDirectory.listAll()).contains(NON_EXISTENT_FILE));
        assertThrows(NoSuchFileException.class, () -> compositeDirectory.fileLength(NON_EXISTENT_FILE));
    }

    public void testCreateOutput() throws IOException {
        try (IndexOutput indexOutput = compositeDirectory.createOutput(NEW_FILE, IOContext.DEFAULT)) {
            // File not present in FileCache until the indexOutput is Closed
            assertNull(fileCache.get(localDirectory.getDirectory().resolve(NEW_FILE)));
        }
        // File present in FileCache after the indexOutput is Closed
        assertNotNull(fileCache.get(localDirectory.getDirectory().resolve(NEW_FILE)));
    }

    public void testSync() throws IOException {
        // All the files in the below list are present either locally or on remote, so sync should work as expected
        Collection<String> names = List.of("_0.cfe", "_0.cfs", "_0.si", "_1.cfe", "_2.cfe", "segments_1");
        compositeDirectory.sync(names);
        // Deleting file _1.cfe and then adding its blocks locally so that full file is not present but block files are present in local
        // State of _1.cfe file after these operations - not present in remote, full file not present locally but blocks present in local
        compositeDirectory.deleteFile("_1.cfe");
        addFilesToDirectory(new String[] { "_1.cfe_block_0", "_1.cfe_block_2" });
        // Sync should work as expected since blocks are present in local
        compositeDirectory.sync(List.of("_1.cfe"));
        // Below list contains a non-existent file, hence will throw an error
        Collection<String> names1 = List.of("_0.cfe", "_0.cfs", "_0.si", "_1.cfe", "_2.cfe", "segments_1", "non_existent_file");
        assertThrows(NoSuchFileException.class, () -> compositeDirectory.sync(names1));
    }

    public void testRename() throws IOException {
        // Rename should work as expected for file present in directory
        assertTrue(existsInCompositeDirectory(FILE_PRESENT_LOCALLY));
        compositeDirectory.rename(FILE_PRESENT_LOCALLY, "_1_new.cfe");
        // Should throw error for file not present
        assertThrows(NoSuchFileException.class, () -> compositeDirectory.rename(NON_EXISTENT_FILE, "_1_new.cfe"));
    }

    public void testOpenInput() throws IOException {
        // File not present in Directory
        assertFalse(existsInCompositeDirectory(NON_EXISTENT_FILE));
        assertThrows(NoSuchFileException.class, () -> compositeDirectory.openInput(NON_EXISTENT_FILE, IOContext.DEFAULT));

        // Temp file, read directly form local directory
        assertTrue(existsInLocalDirectory(TEMP_FILE) && FileTypeUtils.isTempFile(TEMP_FILE));
        assertEquals(
            compositeDirectory.openInput(TEMP_FILE, IOContext.DEFAULT).toString(),
            localDirectory.openInput(TEMP_FILE, IOContext.DEFAULT).toString()
        );

        // File present in file cache
        assertNotNull(fileCache.get(getFilePath(FILE_PRESENT_LOCALLY)));
        assertTrue(compositeDirectory.openInput(FILE_PRESENT_LOCALLY, IOContext.DEFAULT) instanceof FileCachedIndexInput);

        // File present in Remote
        assertFalse(existsInLocalDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        assertTrue(existsInRemoteDirectory(FILE_PRESENT_IN_REMOTE_ONLY));
        assertTrue(compositeDirectory.openInput(FILE_PRESENT_IN_REMOTE_ONLY, IOContext.DEFAULT) instanceof OnDemandBlockSnapshotIndexInput);
    }

    public void testClose() throws IOException {
        // Similar to delete, when close is called existing openInput should be able to function properly but new requests should not be
        // served
        IndexInput indexInput = compositeDirectory.openInput(FILE_PRESENT_LOCALLY, IOContext.DEFAULT);
        compositeDirectory.close();
        // Any operations after close will throw AlreadyClosedException
        assertThrows(AlreadyClosedException.class, () -> compositeDirectory.openInput(FILE_PRESENT_LOCALLY, IOContext.DEFAULT));
        // Existing open IndexInputs will be served
        indexInput.getFilePointer();
        indexInput.close();
        assertThrows(RuntimeException.class, indexInput::getFilePointer);
        assertThrows(AlreadyClosedException.class, () -> compositeDirectory.close());
    }

    public void testAfterSyncToRemote() throws IOException {
        // File will be present locally until uploaded to Remote
        assertTrue(existsInLocalDirectory(FILE_PRESENT_LOCALLY));
        compositeDirectory.afterSyncToRemote(FILE_PRESENT_LOCALLY);
        fileCache.prune();
        // After uploading to Remote, refCount will be decreased by 1 making it 0 and will be evicted if cache is pruned
        assertFalse(existsInLocalDirectory(FILE_PRESENT_LOCALLY));
        // Asserting file is not present in FileCache
        assertNull(fileCache.get(getFilePath(FILE_PRESENT_LOCALLY)));
    }

    public void testOpenInputWithClosedCachedInput() throws Exception {
        // Setup: Create a file and get it into cache
        try (IndexOutput indexOutput = compositeDirectory.createOutput(NEW_FILE, IOContext.DEFAULT)) {
            indexOutput.writeString("test data");
        }

        // Get the cached input and close it
        Path key = getFilePath(NEW_FILE);
        RestoredCachedIndexInput restoredCachedIndexInput = new RestoredCachedIndexInput(0);
        CachedIndexInput cachedInput = fileCache.get(key);
        cachedInput.close();
        // replace the original index input with RestoredCachedIndexInput
        fileCache.put(key, restoredCachedIndexInput);

        // Verify that we can still open the file and get a valid input
        IndexInput input = compositeDirectory.openInput(NEW_FILE, IOContext.DEFAULT);
        assertNotNull(input);
        assertTrue(input instanceof FileCachedIndexInput);
        input.close();
    }

    public void testOpenInputAfterFileCacheEviction() throws IOException {
        // First create and cache the file locally
        try (IndexOutput indexOutput = compositeDirectory.createOutput(FILE_PRESENT_IN_REMOTE_ONLY, IOContext.DEFAULT)) {
            indexOutput.writeString("test data");
        }

        // Clear the file cache
        fileCache.clear();

        // Should still be able to open input, now from remote
        IndexInput input = compositeDirectory.openInput(FILE_PRESENT_IN_REMOTE_ONLY, IOContext.DEFAULT);
        assertNotNull(input);
        assertTrue(input instanceof OnDemandBlockSnapshotIndexInput);
        input.close();
    }

    public void testOpenInputThrowsIOException() throws IOException {
        // Use FILE_PRESENT_LOCALLY ("_1.cfe") which is already set up locally
        // Corrupt the local file to cause IOException
        Path filePath = getFilePath(NEW_FILE);
        try (IndexOutput output = localDirectory.createOutput(NEW_FILE, IOContext.DEFAULT)) {
            output.writeString("corrupted data");
        }

        assertThrows(IOException.class, () -> compositeDirectory.openInput(NEW_FILE, IOContext.DEFAULT));
    }

    private void addFilesToDirectory(String[] files) throws IOException {
        for (String file : files) {
            IndexOutput indexOutput = compositeDirectory.createOutput(file, IOContext.DEFAULT);
            indexOutput.close();
        }
    }

    private void removeExtraFSFiles() throws IOException {
        HashSet<String> allFiles = new HashSet<>(Arrays.asList(localDirectory.listAll()));
        allFiles.stream().filter(FileTypeUtils::isExtraFSFile).forEach(file -> {
            try {
                localDirectory.deleteFile(file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean existsInLocalDirectory(String name) throws IOException {
        return Arrays.asList(localDirectory.listAll()).contains(name);
    }

    private boolean existsInRemoteDirectory(String name) throws IOException {
        return Arrays.asList(remoteSegmentStoreDirectory.listAll()).contains(name);
    }

    private boolean existsInCompositeDirectory(String name) throws IOException {
        return Arrays.asList(compositeDirectory.listAll()).contains(name);
    }

    private Path getFilePath(String name) {
        return localDirectory.getDirectory().resolve(name);
    }
}
