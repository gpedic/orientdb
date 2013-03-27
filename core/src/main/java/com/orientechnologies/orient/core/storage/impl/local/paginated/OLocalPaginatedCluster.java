/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
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
/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
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
package com.orientechnologies.orient.core.storage.impl.local.paginated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.orientechnologies.common.concur.resource.OSharedResourceAdaptive;
import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageClusterConfiguration;
import com.orientechnologies.orient.core.config.OStorageFileConfiguration;
import com.orientechnologies.orient.core.config.OStoragePhysicalClusterConfigurationLocal;
import com.orientechnologies.orient.core.config.OStorageSegmentConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.id.OClusterPosition;
import com.orientechnologies.orient.core.id.OClusterPositionFactory;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.hashindex.local.cache.ODiskCache;
import com.orientechnologies.orient.core.storage.OCluster;
import com.orientechnologies.orient.core.storage.OClusterEntryIterator;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.fs.OFile;
import com.orientechnologies.orient.core.storage.fs.OFileFactory;
import com.orientechnologies.orient.core.storage.impl.local.OSingleFileSegment;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;
import com.orientechnologies.orient.core.storage.impl.local.OStorageVariableParser;
import com.orientechnologies.orient.core.version.ORecordVersion;

/**
 * @author Andrey Lomakin
 * @since 22.03.13
 */
public class OLocalPaginatedCluster extends OSharedResourceAdaptive implements OCluster {
  private static final String                       DEF_EXTENSION                = ".pcl";
  private static final String                       CLUSTER_STATE_FILE_EXTENSION = ".pls";

  public static final String                        TYPE                         = "PHYSICAL";

  private ODiskCache                                diskCache;

  private String                                    name;
  private OStorageLocal                             storageLocal;
  private volatile int                              id;
  private long                                      fileId;

  private long                                      size;
  private long                                      recordsSize;

  private final ODirectMemory                       directMemory                 = ODirectMemoryFactory.INSTANCE.directMemory();

  private OStoragePhysicalClusterConfigurationLocal config;

  private OSingleFileSegment                        clusterStateHolder;

  private long[]                                    freePageIndexes              = new long[48];

  public OLocalPaginatedCluster() {
    super(OGlobalConfiguration.ENVIRONMENT_CONCURRENT.getValueAsBoolean());
    for (int i = 0; i < freePageIndexes.length; i++)
      freePageIndexes[i] = -1;
  }

  @Override
  public void configure(OStorage storage, int id, String clusterName, String location, int dataSegmentId, Object... parameters)
      throws IOException {
    acquireExclusiveLock();
    try {
      config = new OStoragePhysicalClusterConfigurationLocal(storage.getConfiguration(), id, -1);
      config.name = clusterName;

      init((OStorageLocal) storage, config);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void configure(OStorage storage, OStorageClusterConfiguration config) throws IOException {
    acquireExclusiveLock();
    try {
      init((OStorageLocal) storage, config);
    } finally {
      releaseExclusiveLock();
    }
  }

  private void init(OStorageLocal storage, OStorageClusterConfiguration config) throws IOException {
    OFileUtils.checkValidName(config.getName());

    this.config = (OStoragePhysicalClusterConfigurationLocal) config;

    storageLocal = storage;
    diskCache = storageLocal.getDiskCache();
    name = config.getName();
    this.id = config.getId();

    OStorageFileConfiguration clusterStateConfiguration = new OStorageFileConfiguration(null,
        OStorageVariableParser.DB_PATH_VARIABLE + "/" + config.getName() + CLUSTER_STATE_FILE_EXTENSION, OFileFactory.CLASSIC,
        "1024", "50%");
    clusterStateHolder = new OSingleFileSegment(storage, clusterStateConfiguration);
  }

  @Override
  public void create(int startSize) throws IOException {
    acquireExclusiveLock();
    try {
      final OStorageSegmentConfiguration fileConfiguration = new OStorageSegmentConfiguration(storageLocal.getConfiguration(),
          name, id);
      fileConfiguration.fileType = OFileFactory.CLASSIC;
      fileId = diskCache.openFile(fileConfiguration, DEF_EXTENSION);
      clusterStateHolder.create(-1);

      if (config.root.clusters.size() <= config.id)
        config.root.clusters.add(config);
      else
        config.root.clusters.set(config.id, config);

      long pagePointer = diskCache.allocateAndLockForWrite(fileId, 0);
      try {
        OLocalPage localPage = new OLocalPage(pagePointer, true);

        localPage.setNextPage(-1);
        localPage.setPrevPage(-1);

        int freePageIndex = calculateFreePageIndex(localPage);
        assert freePageIndex == freePageIndexes.length - 1;

        freePageIndexes[freePageIndex] = 0;
      } finally {
        diskCache.releaseWriteLock(fileId, 0);
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void open() throws IOException {
    acquireExclusiveLock();
    try {
      final OStorageSegmentConfiguration fileConfiguration = new OStorageSegmentConfiguration(storageLocal.getConfiguration(),
          name, id);
      fileConfiguration.fileType = OFileFactory.CLASSIC;
      fileId = diskCache.openFile(fileConfiguration, DEF_EXTENSION);
      clusterStateHolder.open();

      loadClusterState();
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void close() throws IOException {
    acquireExclusiveLock();
    try {
      synch();

      diskCache.closeFile(fileId);
      clusterStateHolder.close();
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void delete() throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.deleteFile(fileId);
      clusterStateHolder.delete();
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void set(ATTRIBUTES attribute, Object value) throws IOException {
    if (attribute == null)
      throw new IllegalArgumentException("attribute is null");

    final String stringValue = value != null ? value.toString() : null;

    acquireExclusiveLock();
    try {

      switch (attribute) {
      case NAME:
        setNameInternal(stringValue);
        break;
      }

    } finally {
      releaseExclusiveLock();
    }

  }

  private void setNameInternal(String newName) throws IOException {
    diskCache.renameFile(fileId, this.name, newName);
    clusterStateHolder.rename(name, newName);
  }

  @Override
  public void convertToTombstone(OClusterPosition iPosition) throws IOException {
    throw new UnsupportedOperationException("convertToTombstone");
  }

  public OClusterPosition createRecord(final byte[] content, final ORecordVersion recordVersion, final byte recordType)
      throws IOException {
    acquireExclusiveLock();
    try {
      int entryContentLength = content.length + 2 * OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE;

      if (entryContentLength < OLocalPage.MAX_RECORD_SIZE) {
        byte[] entryContent = new byte[entryContentLength];

        int entryPosition = 0;
        entryContent[entryPosition] = recordType;
        entryPosition++;

        System.arraycopy(content, 0, entryContent, entryPosition, content.length);
        entryPosition += content.length;

        entryContent[entryPosition] = 1;
        entryPosition++;

        OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryPosition);

        size++;
        return OClusterPositionFactory.INSTANCE.valueOf(addEntry(recordVersion, entryContent));
      } else {
        int entrySize = content.length + OByteSerializer.BYTE_SIZE;

        int fullEntryPosition = 0;
        byte[] fullEntry = new byte[entrySize];

        fullEntry[fullEntryPosition] = recordType;
        fullEntryPosition++;

        System.arraycopy(content, 0, fullEntry, fullEntryPosition, content.length);

        long prevPageRecordPointer = -1;
        long firstPagePointer = -1;
        int from = 0;
        int to = from + (OLocalPage.MAX_RECORD_SIZE - OByteSerializer.BYTE_SIZE - OLongSerializer.LONG_SIZE);

        do {
          byte[] entryContent = new byte[to - from + OByteSerializer.BYTE_SIZE + OLongSerializer.LONG_SIZE];
          System.arraycopy(fullEntry, from, entryContent, 0, to - from);

          if (from > 0)
            entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 0;
          else
            entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 1;

          OLongSerializer.INSTANCE.serializeNative(-1L, entryContent, entryContent.length - OLongSerializer.LONG_SIZE);

          final long addedPagePointer = addEntry(recordVersion, entryContent);
          if (firstPagePointer == -1)
            firstPagePointer = addedPagePointer;

          if (prevPageRecordPointer >= 0) {

            long prevPageIndex = prevPageRecordPointer >>> 16;
            int prevPageRecordPosition = (int) (prevPageRecordPointer & 0xFFFF);

            long prevPageMemoryPointer = diskCache.loadAndLockForWrite(fileId, prevPageIndex);
            try {
              final OLocalPage prevPage = new OLocalPage(prevPageMemoryPointer, false);

              long recordPointer = prevPage.getRecordPointer(prevPageRecordPosition);
              int prevPageRecordSize = prevPage.getRecordSize(prevPageRecordPosition);

              OLongSerializer.INSTANCE.serializeInDirectMemory(addedPagePointer, directMemory, recordPointer + prevPageRecordSize
                  - OLongSerializer.LONG_SIZE);
            } finally {
              diskCache.releaseWriteLock(fileId, prevPageIndex);
            }
          }

          prevPageRecordPointer = addedPagePointer;
          from = to;
          to = to + (OLocalPage.MAX_RECORD_SIZE - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
          if (to > fullEntry.length)
            to = fullEntry.length;

        } while (from < to);

        size++;
        return OClusterPositionFactory.INSTANCE.valueOf(firstPagePointer);
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  public ORawBuffer readRecord(OClusterPosition clusterPosition) throws IOException {
    acquireSharedLock();
    try {
      long pagePointer = clusterPosition.longValue();
      int recordPosition = (int) (pagePointer & 0xFFFF);

      long pageIndex = pagePointer >>> 16;

      if (diskCache.getFilledUpTo(fileId) < pageIndex)
        return null;

      final List<byte[]> recordChunks = new ArrayList<byte[]>();
      int contentSize = 0;

      long nextPagePointer = -1;
      ORecordVersion recordVersion = null;
      do {
        long pointer = diskCache.loadAndLockForRead(fileId, pageIndex);
        try {
          final OLocalPage localPage = new OLocalPage(pointer, false);

          long recordPointer = localPage.getRecordPointer(recordPosition);

          if (recordPointer == ODirectMemory.NULL_POINTER) {
            if (recordChunks.isEmpty())
              return null;
            else
              throw new OStorageException("Content of record " + new ORecordId(id, clusterPosition) + " was broken.");
          }

          byte[] content = directMemory.get(recordPointer, localPage.getRecordSize(recordPosition));

          if (recordVersion == null)
            recordVersion = localPage.getRecordVersion(recordPosition);

          recordChunks.add(content);
          nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
          contentSize += content.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;
        } finally {
          diskCache.releaseReadLock(fileId, pageIndex);
        }

        pageIndex = nextPagePointer >>> 16;
        recordPosition = (int) (nextPagePointer & 0xFFFF);
      } while (nextPagePointer >= 0);

      byte[] fullContent;
      if (recordChunks.size() == 1)
        fullContent = recordChunks.get(0);
      else {
        fullContent = new byte[contentSize + OLongSerializer.LONG_SIZE + OByteSerializer.BYTE_SIZE];
        int fullContentPosition = 0;
        for (byte[] recordChuck : recordChunks) {
          System.arraycopy(recordChuck, 0, fullContent, fullContentPosition, recordChuck.length - OLongSerializer.LONG_SIZE
              - OByteSerializer.BYTE_SIZE);
          fullContentPosition += recordChuck.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE;
        }
      }

      int fullContentPosition = 0;

      byte recordType = fullContent[fullContentPosition];
      fullContentPosition++;

      byte[] recordContent = new byte[fullContent.length - 2 * OByteSerializer.BYTE_SIZE - OLongSerializer.LONG_SIZE];
      System.arraycopy(fullContent, fullContentPosition, recordContent, 0, recordContent.length);

      return new ORawBuffer(recordContent, recordVersion, recordType);
    } finally {
      releaseSharedLock();
    }
  }

  public boolean deleteRecord(OClusterPosition clusterPosition) throws IOException {
    acquireExclusiveLock();
    try {
      long pagePointer = clusterPosition.longValue();
      int recordPosition = (int) (pagePointer & 0xFFFF);

      long pageIndex = pagePointer >>> 16;

      if (diskCache.getFilledUpTo(fileId) < pageIndex)
        return false;

      long nextPagePointer = -1;
      int removedContentSize = 0;
      do {
        long pointer = diskCache.loadAndLockForWrite(fileId, pageIndex);
        int initialFreePageIndex;
        try {
          final OLocalPage localPage = new OLocalPage(pointer, false);
          initialFreePageIndex = calculateFreePageIndex(localPage);

          long recordPointer = localPage.getRecordPointer(recordPosition);
          if (recordPointer == ODirectMemory.NULL_POINTER) {
            if (removedContentSize == 0)
              return false;
            else
              throw new OStorageException("Content of record " + new ORecordId(id, clusterPosition) + " was broken.");
          }

          byte[] content = directMemory.get(recordPointer, localPage.getRecordSize(recordPosition));

          int initialFreeSpace = localPage.getFreeSpace();
          localPage.deleteRecord(recordPosition);

          removedContentSize += localPage.getFreeSpace() - initialFreeSpace;
          nextPagePointer = OLongSerializer.INSTANCE.deserializeNative(content, content.length - OLongSerializer.LONG_SIZE);
        } finally {
          diskCache.releaseWriteLock(fileId, pageIndex);
        }

        updateFreePagesIndex(initialFreePageIndex, pageIndex);

        pageIndex = nextPagePointer >>> 16;
        recordPosition = (int) (nextPagePointer & 0xFFFF);
      } while (nextPagePointer >= 0);

      size--;
      recordsSize -= removedContentSize;

      return true;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void updateRecord(OClusterPosition clusterPosition, final byte[] content, final ORecordVersion recordVersion,
      final byte recordType) throws IOException {
    acquireExclusiveLock();
    try {
      long pagePointer = clusterPosition.longValue();
      int recordPosition = (int) (pagePointer & 0xFFFF);

      long firstPageIndex = pagePointer >>> 16;

      if (diskCache.getFilledUpTo(fileId) < firstPageIndex)
        return;

      int deleteContentSize;
      long firstPagePointer = diskCache.loadAndLockForWrite(fileId, firstPageIndex);
      int firstPageFreeIndex;
      try {
        final OLocalPage firstPage = new OLocalPage(firstPagePointer, false);
        firstPageFreeIndex = calculateFreePageIndex(firstPage);

        long oldRecordChunkPointer = firstPage.getRecordPointer(recordPosition);
        if (oldRecordChunkPointer == ODirectMemory.NULL_POINTER)
          return;

        long nextPagePointer = OLongSerializer.INSTANCE.deserializeFromDirectMemory(directMemory,
            oldRecordChunkPointer + firstPage.getRecordSize(recordPosition) - OLongSerializer.LONG_SIZE);

        int freeSpace = firstPage.getFreeSpace();
        firstPage.deleteRecord(recordPosition);
        deleteContentSize = firstPage.getFreeSpace() - freeSpace;

        while (nextPagePointer >= 0) {
          long secondaryPageIndex = nextPagePointer >>> 16;
          int secondaryRecordPosition = (int) (nextPagePointer & 0xFFFF);

          long pointer = diskCache.loadAndLockForWrite(fileId, secondaryPageIndex);
          try {
            OLocalPage localPage = new OLocalPage(pointer, false);
            int secondaryFreePageIndex = calculateFreePageIndex(localPage);
            oldRecordChunkPointer = localPage.getRecordPointer(secondaryRecordPosition);

            if (oldRecordChunkPointer == ODirectMemory.NULL_POINTER)
              throw new OStorageException("Data for record with id " + new ORecordId(id, clusterPosition) + " are broken.");

            nextPagePointer = OLongSerializer.INSTANCE.deserializeFromDirectMemory(directMemory,
                oldRecordChunkPointer + localPage.getRecordSize(recordPosition) - OLongSerializer.LONG_SIZE);

            freeSpace = localPage.getFreeSpace();
            localPage.deleteRecord(secondaryRecordPosition);
            deleteContentSize += localPage.getFreeSpace() - freeSpace;

            updateFreePagesIndex(secondaryFreePageIndex, secondaryPageIndex);
          } finally {
            diskCache.releaseWriteLock(fileId, secondaryPageIndex);
          }
        }

        recordsSize -= deleteContentSize;

        int entrySize = content.length + OByteSerializer.BYTE_SIZE;

        int fullEntryPosition = 0;
        byte[] fullEntry = new byte[entrySize];

        fullEntry[fullEntryPosition] = recordType;
        fullEntryPosition++;

        System.arraycopy(content, 0, fullEntry, fullEntryPosition, content.length);

        nextPagePointer = -1;
        int to = fullEntry.length;
        int from = to - (OLocalPage.MAX_RECORD_SIZE - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
        if (from < 0)
          from = 0;

        byte[] entryContent = new byte[from - to + OLongSerializer.LONG_SIZE + OByteSerializer.BYTE_SIZE];
        System.arraycopy(fullEntry, from, entryContent, 0, from - to);

        entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 0;
        OLongSerializer.INSTANCE.serializeNative(nextPagePointer, entryContent, entryContent.length - OLongSerializer.LONG_SIZE);

        int initialFreeSpace = firstPage.getFreeSpace();
        nextPagePointer = (firstPageIndex << 16) | firstPage.appendRecord(recordVersion, entryContent);
        assert nextPagePointer == firstPagePointer;

        recordsSize += initialFreeSpace - firstPage.getFreeSpace();

        while (from > to) {
          entryContent = new byte[from - to + OLongSerializer.LONG_SIZE + OByteSerializer.BYTE_SIZE];
          System.arraycopy(fullEntry, from, entryContent, 0, from - to);

          entryContent[entryContent.length - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE] = 0;

          OLongSerializer.INSTANCE.serializeNative(nextPagePointer, entryContent, entryContent.length - OLongSerializer.LONG_SIZE);
          nextPagePointer = addEntry(recordVersion, entryContent);

          to = from;
          from = from - (OLocalPage.MAX_RECORD_SIZE - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE);
          if (from < 0)
            from = 0;
        }
      } finally {
        diskCache.releaseWriteLock(fileId, firstPageIndex);
      }

      updateFreePagesIndex(firstPageFreeIndex, firstPageIndex);
    } finally {
      releaseExclusiveLock();
    }
  }

  private long addEntry(ORecordVersion recordVersion, byte[] entryContent) throws IOException {
    int freePagePosition = entryContent.length / 1024;
    freePagePosition -= 16;
    if (freePagePosition < 0)
      freePagePosition = 0;

    long pageIndex;
    do {
      pageIndex = freePageIndexes[freePagePosition];
      freePagePosition++;
    } while (pageIndex < 0 && freePagePosition < 48);

    boolean newRecord = false;
    if (pageIndex < 0) {
      pageIndex = diskCache.getFilledUpTo(fileId);
      newRecord = true;
    }

    long pagePointer = diskCache.loadAndLockForWrite(fileId, pageIndex);
    int position;
    int freePageIndex;
    try {
      final OLocalPage localPage = new OLocalPage(pagePointer, newRecord);
      freePageIndex = calculateFreePageIndex(localPage);
      int initialFreeSpace = localPage.getFreeSpace();

      position = localPage.appendRecord(recordVersion, entryContent);
      assert position >= 0;

      int freeSpace = localPage.getFreeSpace();
      recordsSize += initialFreeSpace - freeSpace;
    } finally {
      diskCache.releaseWriteLock(fileId, pageIndex);
    }

    updateFreePagesIndex(freePageIndex, pageIndex);

    return (pageIndex << 16) | position;
  }

  private void updateFreePagesIndex(int prevFreePageIndex, long pageIndex) throws IOException {
    long pointer = diskCache.loadAndLockForWrite(fileId, pageIndex);
    try {
      final OLocalPage localPage = new OLocalPage(pointer, false);

      int newFreePageIndex = calculateFreePageIndex(localPage);

      long nextPageIndex = localPage.getNextPage();
      long prevPageIndex = localPage.getPrevPage();

      if (prevFreePageIndex == newFreePageIndex)
        return;

      if (prevPageIndex >= 0) {
        long prevPagePointer = diskCache.loadAndLockForWrite(fileId, prevPageIndex);
        try {
          final OLocalPage prevPage = new OLocalPage(prevPagePointer, false);
          prevPage.setNextPage(nextPageIndex);
        } finally {
          diskCache.releaseWriteLock(fileId, prevPageIndex);
        }
      }

      if (nextPageIndex >= 0) {
        long nextPagePointer = diskCache.loadAndLockForWrite(fileId, nextPageIndex);
        try {
          final OLocalPage nextPage = new OLocalPage(nextPagePointer, false);
          nextPage.setPrevPage(prevPageIndex);
        } finally {
          diskCache.releaseWriteLock(fileId, nextPageIndex);
        }
      }

      if (prevFreePageIndex < 0 && newFreePageIndex < 0)
        return;

      if (prevFreePageIndex >= 0) {
        freePageIndexes[prevFreePageIndex] = nextPageIndex;

        // !!!

        localPage.setNextPage(-1);
        localPage.setPrevPage(-1);
      }

      if (newFreePageIndex >= 0) {
        long oldFreePage = freePageIndexes[newFreePageIndex];
        if (oldFreePage >= 0) {
          long oldFreePagePointer = diskCache.loadAndLockForWrite(fileId, oldFreePage);
          try {
            final OLocalPage oldFreeLocalPage = new OLocalPage(oldFreePagePointer, false);
            oldFreeLocalPage.setPrevPage(pageIndex);
          } finally {
            diskCache.releaseWriteLock(fileId, oldFreePage);
          }

          localPage.setNextPage(oldFreePage);
          localPage.setPrevPage(-1);
        }

        freePageIndexes[newFreePageIndex] = pageIndex;
      } else {
        localPage.setNextPage(-1);
        localPage.setPrevPage(-1);
      }
    } finally {
      diskCache.releaseWriteLock(fileId, pageIndex);
    }
  }

  private int calculateFreePageIndex(OLocalPage localPage) {
    int newFreePageIndex;
    if (localPage.isEmpty())
      newFreePageIndex = freePageIndexes.length - 1;
    else {
      newFreePageIndex = (localPage.getFreeSpace() - 1023) / 1024;

      newFreePageIndex -= 16;
    }
    return newFreePageIndex;
  }

  @Override
  public long getTombstonesCount() {
    return 0;
  }

  @Override
  public boolean hasTombstonesSupport() {
    return false;
  }

  @Override
  public void truncate() throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.truncateFile(fileId);
      clusterStateHolder.truncate();

      size = 0;
      recordsSize = 0;

      for (int i = 0; i < freePageIndexes.length; i++)
        freePageIndexes[i] = -1;
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public int getDataSegmentId() {
    return -1;
  }

  @Override
  public boolean addPhysicalPosition(OPhysicalPosition iPPosition) throws IOException {
    throw new UnsupportedOperationException("addPhysicalPosition");
  }

  @Override
  public OPhysicalPosition getPhysicalPosition(OPhysicalPosition position) throws IOException {
    acquireSharedLock();
    try {
      OClusterPosition clusterPosition = position.clusterPosition;
      long pagePointer = clusterPosition.longValue();

      long pageIndex = pagePointer >>> 16;
      int recordPosition = (int) (pagePointer & 0xFFFF);

      long pagesCount = diskCache.getFilledUpTo(fileId);
      if (pageIndex >= pagesCount)
        return null;

      long pointer = diskCache.loadAndLockForRead(fileId, pageIndex);
      try {
        final OLocalPage localPage = new OLocalPage(pagePointer, false);
        long recordPointer = localPage.getRecordPointer(recordPosition);

        if (recordPointer == ODirectMemory.NULL_POINTER)
          return null;

        int recordSize = localPage.getRecordSize(recordPosition);
        if (directMemory.getByte(recordPointer + recordSize - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE) == 0)
          return null;

        final OPhysicalPosition physicalPosition = new OPhysicalPosition();
        physicalPosition.dataSegmentId = -1;
        physicalPosition.dataSegmentPos = -1;
        physicalPosition.recordSize = -1;

        physicalPosition.recordType = directMemory.getByte(recordPointer);
        physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
        physicalPosition.clusterPosition = position.clusterPosition;

        return physicalPosition;
      } finally {
        diskCache.releaseReadLock(fileId, pageIndex);
      }

    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void updateDataSegmentPosition(OClusterPosition iPosition, int iDataSegmentId, long iDataPosition) throws IOException {
    throw new UnsupportedOperationException("updateDataSegmentPosition");
  }

  @Override
  public void removePhysicalPosition(OClusterPosition iPosition) throws IOException {
    throw new UnsupportedOperationException("updateDataSegmentPosition");
  }

  @Override
  public void updateRecordType(OClusterPosition iPosition, byte iRecordType) throws IOException {
    throw new UnsupportedOperationException("updateRecordType");
  }

  @Override
  public void updateVersion(OClusterPosition iPosition, ORecordVersion iVersion) throws IOException {
    throw new UnsupportedOperationException("updateVersion");
  }

  @Override
  public long getEntries() {
    return size;
  }

  @Override
  public OClusterPosition getFirstPosition() throws IOException {
    acquireSharedLock();
    try {
      final OPhysicalPosition[] physicalPositions = findFirstPhysicalPosition(0, 0);
      if (physicalPositions.length == 0)
        return OClusterPosition.INVALID_POSITION;

      return physicalPositions[0].clusterPosition;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OClusterPosition getLastPosition() throws IOException {
    acquireSharedLock();
    try {
      long pagesCount = diskCache.getFilledUpTo(fileId);
      for (long i = pagesCount - 1; i >= 0; i--) {
        long pagePointer = diskCache.loadAndLockForRead(fileId, i);
        try {
          final OLocalPage localPage = new OLocalPage(pagePointer, false);
          final int recordsCount = localPage.getRecordsCount();

          if (recordsCount > 0) {
            int recordPosition = Integer.MAX_VALUE;

            for (int n = 0; n < recordsCount; n++) {
              recordPosition = localPage.findLastRecord(recordPosition);

              long recordPointer = localPage.getRecordPointer(recordPosition);
              int recordSize = localPage.getRecordSize(recordPosition);

              if (directMemory.getByte(recordPointer + recordSize - OByteSerializer.BYTE_SIZE - OLongSerializer.LONG_SIZE) == 1)
                return OClusterPositionFactory.INSTANCE.valueOf((i << 16) | recordPosition);

              recordPosition--;
            }
          }
        } finally {
          diskCache.releaseReadLock(fileId, i);
        }
      }

      return OClusterPosition.INVALID_POSITION;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void lock() {
    throw new UnsupportedOperationException("lock");
  }

  @Override
  public void unlock() {
    throw new UnsupportedOperationException("unlock");
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void synch() throws IOException {
    acquireSharedLock();
    try {
      storeClusterState();

      diskCache.flushFile(fileId);
      clusterStateHolder.synch();
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public void setSoftlyClosed(boolean softlyClosed) throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.setSoftlyClosed(fileId, softlyClosed);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public String getName() {
    acquireSharedLock();
    try {
      return name;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public long getRecordsSize() throws IOException {
    acquireSharedLock();
    try {
      return recordsSize;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public boolean isHashBased() {
    return false;
  }

  @Override
  public OClusterEntryIterator absoluteIterator() {
    acquireSharedLock();
    try {
      return new OClusterEntryIterator(this);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OPhysicalPosition[] higherPositions(OPhysicalPosition position) throws IOException {
    acquireSharedLock();
    try {
      OClusterPosition clusterPosition = position.clusterPosition;
      long pagePointer = clusterPosition.longValue();

      long pageIndex = pagePointer >>> 16;
      int recordPosition = (int) (pagePointer & 0xFFFF) + 1;

      return findFirstPhysicalPosition(pageIndex, recordPosition);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OPhysicalPosition[] ceilingPositions(OPhysicalPosition position) throws IOException {
    acquireSharedLock();
    try {
      OClusterPosition clusterPosition = position.clusterPosition;
      long pagePointer = clusterPosition.longValue();

      long pageIndex = pagePointer >>> 16;
      int recordPosition = (int) (pagePointer & 0xFFFF);

      return findFirstPhysicalPosition(pageIndex, recordPosition);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OPhysicalPosition[] lowerPositions(OPhysicalPosition position) throws IOException {
    acquireSharedLock();
    try {
      OClusterPosition clusterPosition = position.clusterPosition;
      long pagePointer = clusterPosition.longValue();

      long pageIndex = pagePointer >>> 16;
      int recordPosition = (int) (pagePointer & 0xFFFF) - 1;

      return findLastPhysicalPosition(pageIndex, recordPosition);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OPhysicalPosition[] floorPositions(OPhysicalPosition position) throws IOException {
    acquireSharedLock();
    try {
      OClusterPosition clusterPosition = position.clusterPosition;
      long pagePointer = clusterPosition.longValue();

      long pageIndex = pagePointer >>> 16;
      int recordPosition = (int) (pagePointer & 0xFFFF);

      return findLastPhysicalPosition(pageIndex, recordPosition);
    } finally {
      releaseSharedLock();
    }
  }

  private OPhysicalPosition[] findFirstPhysicalPosition(long pageIndex, int recordPosition) throws IOException {
    long pagesCount = diskCache.getFilledUpTo(fileId);
    pageLoop: for (long i = pageIndex; i < pagesCount; i++) {
      long pointer = diskCache.loadAndLockForRead(fileId, i);

      try {
        final OLocalPage localPage = new OLocalPage(pointer, false);

        int recordsCount = localPage.getRecordsCount();

        if (recordsCount > 0) {
          while (true) {
            recordPosition = localPage.findFirstRecord(recordPosition);
            if (recordPosition < 0) {
              recordPosition = 0;
              continue pageLoop;
            } else {
              long recordPointer = localPage.getRecordPointer(recordPosition);
              int recordSize = localPage.getRecordSize(recordPosition);

              if (directMemory.getByte(recordPointer + recordSize - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE) == 1) {
                OPhysicalPosition physicalPosition = new OPhysicalPosition();

                physicalPosition.clusterPosition = OClusterPositionFactory.INSTANCE.valueOf((i << 16) | recordPosition);
                physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
                physicalPosition.recordType = directMemory.getByte(recordPointer);

                physicalPosition.recordSize = -1;
                physicalPosition.dataSegmentId = -1;
                physicalPosition.dataSegmentPos = -1;

                return new OPhysicalPosition[] { physicalPosition };
              }

              recordPosition++;
            }
          }
        }

      } finally {
        diskCache.releaseReadLock(fileId, i);
      }
    }

    return new OPhysicalPosition[0];
  }

  private OPhysicalPosition[] findLastPhysicalPosition(long pageIndex, int recordPosition) throws IOException {
    long pagesCount = diskCache.getFilledUpTo(fileId);
    long endPageIndex;
    if (pagesCount <= pageIndex) {
      recordPosition = Integer.MAX_VALUE;
      endPageIndex = pagesCount - 1;
    } else {
      endPageIndex = pageIndex;
    }

    pageLoop: for (long i = endPageIndex; i >= 0; i--) {
      long pointer = diskCache.loadAndLockForRead(fileId, i);
      try {
        final OLocalPage localPage = new OLocalPage(pointer, false);

        int recordsCount = localPage.getRecordsCount();

        if (recordsCount > 0) {
          while (true) {
            recordPosition = localPage.findLastRecord(recordPosition);
            if (recordPosition < 0) {
              recordPosition = Integer.MAX_VALUE;
              continue pageLoop;
            } else {
              long recordPointer = localPage.getRecordPointer(recordPosition);
              int recordSize = localPage.getRecordSize(recordPosition);

              if (directMemory.getByte(recordPointer + recordSize - OLongSerializer.LONG_SIZE - OByteSerializer.BYTE_SIZE) == 1) {
                OPhysicalPosition physicalPosition = new OPhysicalPosition();

                physicalPosition.clusterPosition = OClusterPositionFactory.INSTANCE.valueOf((i << 16) | recordPosition);
                physicalPosition.recordVersion = localPage.getRecordVersion(recordPosition);
                physicalPosition.recordType = directMemory.getByte(recordPointer);

                physicalPosition.recordSize = -1;
                physicalPosition.dataSegmentId = -1;
                physicalPosition.dataSegmentPos = -1;

                return new OPhysicalPosition[] { physicalPosition };
              }

              recordPosition--;
            }
          }
        }

      } finally {
        diskCache.releaseReadLock(fileId, i);
      }
    }

    return new OPhysicalPosition[0];
  }

  private void storeClusterState() throws IOException {
    clusterStateHolder.truncate();
    final int stateSize = OIntegerSerializer.INT_SIZE + 2 * OLongSerializer.LONG_SIZE + freePageIndexes.length
        * OLongSerializer.LONG_SIZE;
    OFile file = clusterStateHolder.getFile();
    file.allocateSpace(stateSize);

    long fileOffset = 0;
    file.writeLong(fileOffset, size);
    fileOffset += OLongSerializer.LONG_SIZE;

    file.writeLong(fileOffset, recordsSize);
    fileOffset += OLongSerializer.LONG_SIZE;

    file.writeInt(fileOffset, OIntegerSerializer.INT_SIZE);
    fileOffset += OIntegerSerializer.INT_SIZE;

    for (long freePageIndex : freePageIndexes) {
      file.writeLong(fileOffset, freePageIndex);
      fileOffset += OLongSerializer.LONG_SIZE;
    }
  }

  private void loadClusterState() throws IOException {
    OFile file = clusterStateHolder.getFile();
    long fileOffset = 0;

    size = file.readLong(fileOffset);
    fileOffset += OLongSerializer.LONG_SIZE;

    recordsSize = file.readLong(fileOffset);
    fileOffset += OLongSerializer.LONG_SIZE;

    int freePageIndexesSize = file.readInt(fileOffset);
    fileOffset += OIntegerSerializer.INT_SIZE;

    for (int i = 0; i < freePageIndexesSize; i++) {
      freePageIndexes[i] = file.readLong(fileOffset);
      fileOffset += OLongSerializer.LONG_SIZE;
    }
  }
}