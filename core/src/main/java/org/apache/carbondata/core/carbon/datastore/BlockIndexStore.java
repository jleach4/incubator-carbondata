/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
package org.apache.carbondata.core.carbon.datastore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.carbondata.core.carbon.AbsoluteTableIdentifier;
import org.apache.carbondata.core.carbon.datastore.block.AbstractIndex;
import org.apache.carbondata.core.carbon.datastore.block.BlockIndex;
import org.apache.carbondata.core.carbon.datastore.block.BlockInfo;
import org.apache.carbondata.core.carbon.datastore.block.TableBlockInfo;
import org.apache.carbondata.core.carbon.datastore.exception.IndexBuilderException;
import org.apache.carbondata.core.carbon.metadata.blocklet.DataFileFooter;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.util.CarbonProperties;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.CarbonUtilException;

/**
 * Singleton Class to handle loading, unloading,clearing,storing of the table
 * blocks
 */
public class BlockIndexStore {

  /**
   * singleton instance
   */
  private static final BlockIndexStore CARBONTABLEBLOCKSINSTANCE = new BlockIndexStore();

  /**
   * map to hold the table and its list of blocks
   */
  private Map<AbsoluteTableIdentifier, Map<BlockInfo, AbstractIndex>> tableBlocksMap;

  /**
   * map to maintain segment id to block info map, this map will be used to
   * while removing the block from memory when segment is compacted or deleted
   */
  private Map<AbsoluteTableIdentifier, Map<String, List<BlockInfo>>> segmentIdToBlockListMap;

  /**
   * map of block info to lock object map, while loading the btree this will be filled
   * and removed after loading the tree for that particular block info, this will be useful
   * while loading the tree concurrently so only block level lock will be applied another
   * block can be loaded concurrently
   */
  private Map<BlockInfo, Object> blockInfoLock;

  /**
   * table and its lock object to this will be useful in case of concurrent
   * query scenario when more than one query comes for same table and in that
   * case it will ensure that only one query will able to load the blocks
   */
  private Map<AbsoluteTableIdentifier, Object> tableLockMap;

  /**
   * block info to future task mapping
   * useful when blocklet distribution is enabled and
   * same block is loaded by multiple thread
   */
  private Map<BlockInfo, Future<AbstractIndex>> mapOfBlockInfoToFuture;

  private BlockIndexStore() {
    tableBlocksMap = new ConcurrentHashMap<AbsoluteTableIdentifier, Map<BlockInfo, AbstractIndex>>(
        CarbonCommonConstants.DEFAULT_COLLECTION_SIZE);
    tableLockMap = new ConcurrentHashMap<AbsoluteTableIdentifier, Object>(
        CarbonCommonConstants.DEFAULT_COLLECTION_SIZE);
    blockInfoLock = new ConcurrentHashMap<BlockInfo, Object>();
    segmentIdToBlockListMap = new ConcurrentHashMap<>();
    mapOfBlockInfoToFuture = new ConcurrentHashMap<>();
  }

  /**
   * Return the instance of this class
   *
   * @return singleton instance
   */
  public static BlockIndexStore getInstance() {
    return CARBONTABLEBLOCKSINSTANCE;
  }

  /**
   * below method will be used to load the block which are not loaded and to
   * get the loaded blocks if all the blocks which are passed is loaded then
   * it will not load , else it will load.
   *
   * @param tableBlocksInfos        list of blocks to be loaded
   * @param absoluteTableIdentifier absolute Table Identifier to identify the table
   * @throws IndexBuilderException
   */
  public List<AbstractIndex> loadAndGetBlocks(List<TableBlockInfo> tableBlocksInfos,
      AbsoluteTableIdentifier absoluteTableIdentifier) throws IndexBuilderException {
    AbstractIndex[] loadedBlock = new AbstractIndex[tableBlocksInfos.size()];
    addTableLockObject(absoluteTableIdentifier);

    // get the instance
    Object lockObject = tableLockMap.get(absoluteTableIdentifier);
    Map<BlockInfo, AbstractIndex> tableBlockMapTemp = null;
    int numberOfCores = 1;
    try {
      numberOfCores = Integer.parseInt(CarbonProperties.getInstance()
          .getProperty(CarbonCommonConstants.NUM_CORES,
              CarbonCommonConstants.NUM_CORES_DEFAULT_VAL));
    } catch (NumberFormatException e) {
      numberOfCores = Integer.parseInt(CarbonCommonConstants.NUM_CORES_DEFAULT_VAL);
    }
    ExecutorService executor = Executors.newFixedThreadPool(numberOfCores);
    // Acquire the lock to ensure only one query is loading the table blocks
    // if same block is assigned to both the queries
    List<BlockInfo> blockInfosNeedToLoad = null;
    synchronized (lockObject) {
      tableBlockMapTemp = tableBlocksMap.get(absoluteTableIdentifier);
      // if it is loading for first time
      if (null == tableBlockMapTemp) {
        tableBlockMapTemp = new ConcurrentHashMap<BlockInfo, AbstractIndex>();
        tableBlocksMap.put(absoluteTableIdentifier, tableBlockMapTemp);
      }
      blockInfosNeedToLoad = fillSegmentIdToTableInfoMap(tableBlocksInfos, absoluteTableIdentifier);
    }
    AbstractIndex tableBlock = null;
    int counter = -1;
    for (BlockInfo blockInfo : blockInfosNeedToLoad) {
      counter++;
      // if table block is already loaded then do not load
      // that block
      tableBlock = tableBlockMapTemp.get(blockInfo);
      // if block is not loaded
      if (null == tableBlock) {
        // check any lock object is present in
        // block info lock map
        Object blockInfoLockObject = blockInfoLock.get(blockInfo);
        // if lock object is not present then acquire
        // the lock in block info lock and add a lock object in the map for
        // particular block info, added double checking mechanism to add the lock
        // object so in case of concurrent query we for same block info only one lock
        // object will be added
        if (null == blockInfoLockObject) {
          synchronized (blockInfoLock) {
            // again checking the block info lock, to check whether lock object is present
            // or not if now also not present then add a lock object
            blockInfoLockObject = blockInfoLock.get(blockInfo);
            if (null == blockInfoLockObject) {
              blockInfoLockObject = new Object();
              blockInfoLock.put(blockInfo, blockInfoLockObject);
            }
          }
        }
        //acquire the lock for particular block info
        synchronized (blockInfoLockObject) {
          // check again whether block is present or not to avoid the
          // same block is loaded
          //more than once in case of concurrent query
          tableBlock = tableBlockMapTemp.get(blockInfo);
          // if still block is not present then load the block
          if (null == tableBlock) {
            if (null == mapOfBlockInfoToFuture.get(blockInfo)) {
              mapOfBlockInfoToFuture.put(blockInfo, executor
                  .submit(new BlockLoaderThread(blockInfo, tableBlockMapTemp)));
            }
          } else {
            loadedBlock[counter] = tableBlock;
          }
        }
      } else {
        // if blocks is already loaded then directly set the block at particular position
        //so block will be present in sorted order
        loadedBlock[counter] = tableBlock;
      }
    }
    // shutdown the executor gracefully and wait until all the task is finished
    executor.shutdown();
    try {
      executor.awaitTermination(1, TimeUnit.HOURS);
    } catch (InterruptedException e) {
      throw new IndexBuilderException(e);
    }
    // fill the block which were not loaded before to loaded blocks array
    fillLoadedBlocks(loadedBlock, blockInfosNeedToLoad);
    return Arrays.asList(loadedBlock);
  }

  /**
   * Below method will be used to fill segment id to its block mapping map.
   * it will group all the table block info based on segment id and it will fill
   *
   * @param tableBlockInfos         table block infos
   * @param absoluteTableIdentifier absolute table identifier
   */
  private List<BlockInfo> fillSegmentIdToTableInfoMap(List<TableBlockInfo> tableBlockInfos,
      AbsoluteTableIdentifier absoluteTableIdentifier) {
    Map<String, List<BlockInfo>> map = segmentIdToBlockListMap.get(absoluteTableIdentifier);
    if (null == map) {
      map = new ConcurrentHashMap<String, List<BlockInfo>>();
      segmentIdToBlockListMap.put(absoluteTableIdentifier, map);
    }
    BlockInfo temp = null;
    List<BlockInfo> blockInfosNeedToLoad = new ArrayList<>();

    for (TableBlockInfo info : tableBlockInfos) {
      List<BlockInfo> tempTableBlockInfos = map.get(info.getSegmentId());
      if (null == tempTableBlockInfos) {
        tempTableBlockInfos = new ArrayList<>();
        map.put(info.getSegmentId(), tempTableBlockInfos);
      }
      temp = new BlockInfo(info);
      if (!tempTableBlockInfos.contains(temp)) {
        tempTableBlockInfos.add(temp);
      }
      blockInfosNeedToLoad.add(temp);
    }
    return blockInfosNeedToLoad;
  }

  /**
   * Below method will be used to fill the loaded blocks to the array
   * which will be used for query execution
   *
   * @param loadedBlockArray array of blocks which will be filled
   * @param blocksList       blocks loaded in thread
   * @throws IndexBuilderException in case of any failure
   */
  private void fillLoadedBlocks(AbstractIndex[] loadedBlockArray, List<BlockInfo> blockInfos)
      throws IndexBuilderException {
    for (int i = 0; i < loadedBlockArray.length; i++) {
      if (null == loadedBlockArray[i]) {
        try {
          loadedBlockArray[i] = mapOfBlockInfoToFuture.get(blockInfos.get(i)).get();
        } catch (InterruptedException | ExecutionException e) {
          throw new IndexBuilderException(e);
        }
      }

    }
  }

  private AbstractIndex loadBlock(Map<BlockInfo, AbstractIndex> tableBlockMapTemp,
      BlockInfo blockInfo) throws CarbonUtilException {
    AbstractIndex tableBlock;
    DataFileFooter footer;
    // getting the data file meta data of the block
    footer = CarbonUtil.readMetadatFile(blockInfo.getTableBlockInfo().getFilePath(),
        blockInfo.getTableBlockInfo().getBlockOffset(),
        blockInfo.getTableBlockInfo().getBlockLength());
    tableBlock = new BlockIndex();
    footer.setBlockInfo(blockInfo);
    // building the block
    tableBlock.buildIndex(Arrays.asList(footer));
    tableBlockMapTemp.put(blockInfo, tableBlock);
    // finally remove the lock object from block info lock as once block is loaded
    // it will not come inside this if condition
    blockInfoLock.remove(blockInfo);
    return tableBlock;
  }

  /**
   * Method to add table level lock if lock is not present for the table
   *
   * @param absoluteTableIdentifier
   */
  private synchronized void addTableLockObject(AbsoluteTableIdentifier absoluteTableIdentifier) {
    // add the instance to lock map if it is not present
    if (null == tableLockMap.get(absoluteTableIdentifier)) {
      tableLockMap.put(absoluteTableIdentifier, new Object());
    }
  }

  /**
   * This will be used to remove a particular blocks useful in case of
   * deletion of some of the blocks in case of retention or may be some other
   * scenario
   *
   * @param segmentsToBeRemoved     list of segments to be removed
   * @param absoluteTableIdentifier absolute table identifier
   */
  public void removeTableBlocks(List<String> segmentsToBeRemoved,
      AbsoluteTableIdentifier absoluteTableIdentifier) {
    // get the lock object if lock object is not present then it is not
    // loaded at all
    // we can return from here
    Object lockObject = tableLockMap.get(absoluteTableIdentifier);
    if (null == lockObject) {
      return;
    }
    Map<BlockInfo, AbstractIndex> map = tableBlocksMap.get(absoluteTableIdentifier);
    // if there is no loaded blocks then return
    if (null == map || map.isEmpty()) {
      return;
    }
    Map<String, List<BlockInfo>> segmentIdToBlockInfoMap =
        segmentIdToBlockListMap.get(absoluteTableIdentifier);
    if (null == segmentIdToBlockInfoMap || segmentIdToBlockInfoMap.isEmpty()) {
      return;
    }
    synchronized (lockObject) {
      for (String segmentId : segmentsToBeRemoved) {
        List<BlockInfo> tableBlockInfoList = segmentIdToBlockInfoMap.remove(segmentId);
        if (null == tableBlockInfoList) {
          continue;
        }
        Iterator<BlockInfo> tableBlockInfoIterator = tableBlockInfoList.iterator();
        while (tableBlockInfoIterator.hasNext()) {
          BlockInfo info = tableBlockInfoIterator.next();
          map.remove(info);
        }
      }
    }
  }

  /**
   * remove all the details of a table this will be used in case of drop table
   *
   * @param absoluteTableIdentifier absolute table identifier to find the table
   */
  public void clear(AbsoluteTableIdentifier absoluteTableIdentifier) {
    // removing all the details of table
    tableLockMap.remove(absoluteTableIdentifier);
    tableBlocksMap.remove(absoluteTableIdentifier);
  }

  /**
   * Thread class which will be used to load the blocks
   */
  private class BlockLoaderThread implements Callable<AbstractIndex> {
    /**
     * table block info to block index map
     */
    private Map<BlockInfo, AbstractIndex> tableBlockMap;

    // block info
    private BlockInfo blockInfo;

    private BlockLoaderThread(BlockInfo blockInfo, Map<BlockInfo, AbstractIndex> tableBlockMap) {
      this.tableBlockMap = tableBlockMap;
      this.blockInfo = blockInfo;
    }

    @Override public AbstractIndex call() throws Exception {
      // load and return the loaded blocks
      return loadBlock(tableBlockMap, blockInfo);
    }
  }
}
