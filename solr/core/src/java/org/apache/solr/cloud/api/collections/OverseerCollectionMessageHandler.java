/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud.api.collections;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.AlreadyExistsException;
import org.apache.solr.client.solrj.cloud.autoscaling.BadVersionException;
import org.apache.solr.client.solrj.impl.BaseHttpSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.cloud.LockTree;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.cloud.OverseerMessageHandler;
import org.apache.solr.cloud.OverseerNodePrioritizer;
import org.apache.solr.cloud.OverseerSolrResponse;
import org.apache.solr.cloud.OverseerTaskProcessor;
import org.apache.solr.cloud.Stats;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrCloseable;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CollectionParams.CollectionAction;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.CoreAdminParams.CoreAdminAction;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.component.HttpShardHandlerFactory;
import org.apache.solr.handler.component.ShardHandler;
import org.apache.solr.handler.component.ShardRequest;
import org.apache.solr.handler.component.ShardResponse;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.client.solrj.cloud.autoscaling.Policy.POLICY;
import static org.apache.solr.common.cloud.DocCollection.SNITCH;
import static org.apache.solr.common.cloud.ZkStateReader.BASE_URL_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NODE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.ELECTION_NODE_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.PROPERTY_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.PROPERTY_VALUE_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.REJOIN_AT_HEAD_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.REPLICA_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.SHARD_ID_PROP;
import static org.apache.solr.common.params.CollectionAdminParams.COLLECTION;
import static org.apache.solr.common.params.CollectionAdminParams.COLOCATED_WITH;
import static org.apache.solr.common.params.CollectionAdminParams.WITH_COLLECTION;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.*;
import static org.apache.solr.common.params.CommonAdminParams.ASYNC;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.util.Utils.makeMap;

/**
 * A {@link OverseerMessageHandler} that handles Collections API related
 * overseer messages.
 */
public class OverseerCollectionMessageHandler implements OverseerMessageHandler, SolrCloseable {

  public static final boolean CREATE_NODE_SET_SHUFFLE_DEFAULT = true;
  public static final String CREATE_NODE_SET_SHUFFLE = CollectionAdminParams.CREATE_NODE_SET_SHUFFLE_PARAM;

  public static final String ROUTER = "router";

  public static final String SHARDS_PROP = "shards";

  public static final String REQUESTID = "requestid";

  public static final String COLL_PROP_PREFIX = "property.";

  public static final String ONLY_IF_DOWN = "onlyIfDown";

  public static final String SHARD_UNIQUE = "shardUnique";

  public static final String ONLY_ACTIVE_NODES = "onlyactivenodes";

  static final String SKIP_CREATE_REPLICA_IN_CLUSTER_STATE = "skipCreateReplicaInClusterState";

  public static final Map<String, Object> COLLECTION_PROPS_AND_DEFAULTS = Collections.unmodifiableMap(makeMap(
      ROUTER, DocRouter.DEFAULT_NAME,
      ZkStateReader.REPLICATION_FACTOR, "1",
      ZkStateReader.NRT_REPLICAS, "1",
      ZkStateReader.TLOG_REPLICAS, "0",
      ZkStateReader.PULL_REPLICAS, "0",
      ZkStateReader.MAX_SHARDS_PER_NODE, "1",
      ZkStateReader.AUTO_ADD_REPLICAS, "false",
      DocCollection.RULE, null,
      POLICY, null,
      SNITCH, null,
      WITH_COLLECTION, null,
      COLOCATED_WITH, null));

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final String FAILURE_FIELD = "failure";
  public static final String SUCCESS_FIELD = "success";

  Overseer overseer;
  HttpShardHandlerFactory shardHandlerFactory;
  String adminPath;
  ZkStateReader zkStateReader;
  SolrCloudManager cloudManager;
  String myId;
  Stats stats;
  TimeSource timeSource;

  // Set that tracks collections that are currently being processed by a running task.
  // This is used for handling mutual exclusion of the tasks.

  final private LockTree lockTree = new LockTree();

  public static final Random RANDOM;
  static {
    // We try to make things reproducible in the context of our tests by initializing the random instance
    // based on the current seed
    String seed = System.getProperty("tests.seed");
    if (seed == null) {
      RANDOM = new Random();
    } else {
      RANDOM = new Random(seed.hashCode());
    }
  }

  final Map<CollectionAction, Cmd> commandMap;

  private volatile boolean isClosed;

  public OverseerCollectionMessageHandler(ZkStateReader zkStateReader, String myId,
                                        final HttpShardHandlerFactory shardHandlerFactory,
                                        String adminPath,
                                        Stats stats,
                                        Overseer overseer,
                                        OverseerNodePrioritizer overseerPrioritizer) {
    assert ObjectReleaseTracker.track(this);
    this.zkStateReader = zkStateReader;
    this.shardHandlerFactory = shardHandlerFactory;
    this.adminPath = adminPath;
    this.myId = myId;
    this.stats = stats;
    this.overseer = overseer;
    this.cloudManager = overseer.getSolrCloudManager();
    this.timeSource = cloudManager.getTimeSource();
    this.isClosed = false;
    commandMap = new ImmutableMap.Builder<CollectionAction, Cmd>()
        .put(REPLACENODE, new ReplaceNodeCmd(this))
        .put(DELETENODE, new DeleteNodeCmd(this))
        .put(BACKUP, new BackupCmd(this))
        .put(RESTORE, new RestoreCmd(this))
        .put(CREATESNAPSHOT, new CreateSnapshotCmd(this))
        .put(DELETESNAPSHOT, new DeleteSnapshotCmd(this))
        .put(SPLITSHARD, new SplitShardCmd(this))
        .put(ADDROLE, new OverseerRoleCmd(this, ADDROLE, overseerPrioritizer))
        .put(REMOVEROLE, new OverseerRoleCmd(this, REMOVEROLE, overseerPrioritizer))
        .put(MOCK_COLL_TASK, this::mockOperation)
        .put(MOCK_SHARD_TASK, this::mockOperation)
        .put(MOCK_REPLICA_TASK, this::mockOperation)
        .put(CREATESHARD, new CreateShardCmd(this))
        .put(MIGRATE, new MigrateCmd(this))
            .put(CREATE, new CreateCollectionCmd(this, overseer.getCoreContainer(), cloudManager))
        .put(MODIFYCOLLECTION, this::modifyCollection)
        .put(ADDREPLICAPROP, this::processReplicaAddPropertyCommand)
        .put(DELETEREPLICAPROP, this::processReplicaDeletePropertyCommand)
        .put(BALANCESHARDUNIQUE, this::balanceProperty)
        .put(REBALANCELEADERS, this::processRebalanceLeaders)
        .put(RELOAD, this::reloadCollection)
        .put(DELETE, new DeleteCollectionCmd(this))
        .put(CREATEALIAS, new CreateAliasCmd(this))
        .put(DELETEALIAS, new DeleteAliasCmd(this))
        .put(ALIASPROP, new SetAliasPropCmd(this))
        .put(MAINTAINROUTEDALIAS, new MaintainRoutedAliasCmd(this))
        .put(OVERSEERSTATUS, new OverseerStatusCmd(this))
        .put(DELETESHARD, new DeleteShardCmd(this))
        .put(DELETEREPLICA, new DeleteReplicaCmd(this))
        .put(ADDREPLICA, new AddReplicaCmd(this))
        .put(MOVEREPLICA, new MoveReplicaCmd(this))
        .put(REINDEXCOLLECTION, new ReindexCollectionCmd(this))
        .put(UTILIZENODE, new UtilizeNodeCmd(this))
        .put(RENAME, new RenameCmd(this))
        .build()
    ;
  }

  @Override
  @SuppressWarnings("unchecked")
  public OverseerSolrResponse processMessage(ZkNodeProps message, String operation) throws InterruptedException {
    MDCLoggingContext.setCollection(message.getStr(COLLECTION));
    MDCLoggingContext.setShard(message.getStr(SHARD_ID_PROP));
    MDCLoggingContext.setReplica(message.getStr(REPLICA_PROP));
    log.debug("OverseerCollectionMessageHandler.processMessage : {} , {}", operation, message);

    @SuppressWarnings({"rawtypes"})
    NamedList results = new NamedList();
   // NamedList threadSafeResults = new ConcurrentNamedList();
    try {
      CollectionAction action = getCollectionAction(operation);
      Cmd command = commandMap.get(action);
      if (command != null) {
        command.call(overseer.getZkStateReader().getClusterState(), message, results);
      } else {
        throw new SolrException(ErrorCode.BAD_REQUEST, "Unknown operation:"
            + operation);
      }
    }  catch (InterruptedException e) {
      ParWork.propagateInterrupt(e);
      throw e;
    } catch (Exception e) {
      String collName = message.getStr("collection");
      if (collName == null) collName = message.getStr(NAME);

      if (collName == null) {
        log.error("Operation " + operation + " failed", e);
      } else  {
        log.error("Collection: " + collName + " operation: " + operation
            + " failed", e);
      }

      results.add("Operation " + operation + " caused exception:", e);
      SimpleOrderedMap<Object> nl = new SimpleOrderedMap<>();
      nl.add("msg", e.getMessage());
      nl.add("rspCode", e instanceof SolrException ? ((SolrException)e).code() : -1);
      results.add("exception", nl);
    }
  //  results.addAll(threadSafeResults);
    return new OverseerSolrResponse(results);
  }

  @SuppressForbidden(reason = "Needs currentTimeMillis for mock requests")
  @SuppressWarnings({"unchecked"})
  private void mockOperation(ClusterState state, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results) throws InterruptedException {
    //only for test purposes
    Thread.sleep(message.getInt("sleep", 1));
    if (log.isInfoEnabled()) {
      log.info("MOCK_TASK_EXECUTED time {} data {}", System.currentTimeMillis(), Utils.toJSONString(message));
    }
    results.add("MOCK_FINISHED", System.currentTimeMillis());
  }

  private CollectionAction getCollectionAction(String operation) {
    CollectionAction action = CollectionAction.get(operation);
    if (action == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Unknown operation:" + operation);
    }
    return action;
  }

  @SuppressWarnings({"unchecked"})
  private void reloadCollection(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results) throws KeeperException, InterruptedException {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CoreAdminParams.ACTION, CoreAdminAction.RELOAD.toString());

    String asyncId = message.getStr(ASYNC);
    collectionCmd(message, params, results, Replica.State.ACTIVE, asyncId);
  }

  @SuppressWarnings("unchecked")
  private void processRebalanceLeaders(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results)
      throws Exception {
    checkRequired(message, COLLECTION_PROP, SHARD_ID_PROP, CORE_NAME_PROP, ELECTION_NODE_PROP,
        CORE_NODE_NAME_PROP, BASE_URL_PROP, REJOIN_AT_HEAD_PROP);

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(COLLECTION_PROP, message.getStr(COLLECTION_PROP));
    params.set(SHARD_ID_PROP, message.getStr(SHARD_ID_PROP));
    params.set(REJOIN_AT_HEAD_PROP, message.getStr(REJOIN_AT_HEAD_PROP));
    params.set(CoreAdminParams.ACTION, CoreAdminAction.REJOINLEADERELECTION.toString());
    params.set(CORE_NAME_PROP, message.getStr(CORE_NAME_PROP));
    params.set(CORE_NODE_NAME_PROP, message.getStr(CORE_NODE_NAME_PROP));
    params.set(ELECTION_NODE_PROP, message.getStr(ELECTION_NODE_PROP));
    params.set(BASE_URL_PROP, message.getStr(BASE_URL_PROP));

    String baseUrl = message.getStr(BASE_URL_PROP);
    ShardRequest sreq = new ShardRequest();
    sreq.nodeName = message.getStr(ZkStateReader.CORE_NAME_PROP);
    // yes, they must use same admin handler path everywhere...
    params.set("qt", adminPath);
    sreq.purpose = ShardRequest.PURPOSE_PRIVATE;
    sreq.shards = new String[] {baseUrl};
    sreq.actualShards = sreq.shards;
    sreq.params = params;
    ShardHandler shardHandler = shardHandlerFactory.getShardHandler(overseer.getCoreContainer().getUpdateShardHandler().getTheSharedHttpClient());
    shardHandler.submit(sreq, baseUrl, sreq.params);
  }

  @SuppressWarnings("unchecked")
  private void processReplicaAddPropertyCommand(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results)
      throws Exception {
    checkRequired(message, COLLECTION_PROP, SHARD_ID_PROP, ZkStateReader.NUM_SHARDS_PROP, "shards", REPLICA_PROP, PROPERTY_PROP, PROPERTY_VALUE_PROP);
    Map<String, Object> propMap = new HashMap<>(message.getProperties().size() + 1);
    propMap.put(Overseer.QUEUE_OPERATION, ADDREPLICAPROP.toLower());
    propMap.putAll(message.getProperties());
    ZkNodeProps m = new ZkNodeProps(propMap);
    overseer.offerStateUpdate(Utils.toJSON(m));
  }

  private void processReplicaDeletePropertyCommand(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results)
      throws Exception {
    checkRequired(message, COLLECTION_PROP, SHARD_ID_PROP, REPLICA_PROP, PROPERTY_PROP);
    Map<String, Object> propMap = new HashMap<>(message.getProperties().size() + 1);
    propMap.put(Overseer.QUEUE_OPERATION, DELETEREPLICAPROP.toLower());
    propMap.putAll(message.getProperties());
    ZkNodeProps m = new ZkNodeProps(propMap);
    overseer.offerStateUpdate(Utils.toJSON(m));
  }

  private void balanceProperty(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results) throws Exception {
    if (StringUtils.isBlank(message.getStr(COLLECTION_PROP)) || StringUtils.isBlank(message.getStr(PROPERTY_PROP))) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "The '" + COLLECTION_PROP + "' and '" + PROPERTY_PROP +
              "' parameters are required for the BALANCESHARDUNIQUE operation, no action taken");
    }
    Map<String, Object> m = new HashMap<>(message.getProperties().size() + 1);
    m.put(Overseer.QUEUE_OPERATION, BALANCESHARDUNIQUE.toLower());
    m.putAll(message.getProperties());
    overseer.offerStateUpdate(Utils.toJSON(m));
  }

  /**
   * Get collection status from cluster state.
   * Can return collection status by given shard name.
   *
   *
   * @param collection collection map parsed from JSON-serialized {@link ClusterState}
   * @param name  collection name
   * @param requestedShards a set of shards to be returned in the status.
   *                        An empty or null values indicates <b>all</b> shards.
   * @return map of collection properties
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> getCollectionStatus(Map<String, Object> collection, String name, Set<String> requestedShards) {
    if (collection == null)  {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Collection: " + name + " not found");
    }
    if (requestedShards == null || requestedShards.isEmpty()) {
      return collection;
    } else {
      Map<String, Object> shards = (Map<String, Object>) collection.get("shards");
      Map<String, Object>  selected = new HashMap<>(1);
      for (String selectedShard : requestedShards) {
        if (!shards.containsKey(selectedShard)) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "Collection: " + name + " shard: " + selectedShard + " not found");
        }
        selected.put(selectedShard, shards.get(selectedShard));
        collection.put("shards", selected);
      }
      return collection;
    }
  }

  @SuppressWarnings("unchecked")
  void deleteReplica(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results, Runnable onComplete)
      throws Exception {
    ((DeleteReplicaCmd) commandMap.get(DELETEREPLICA)).deleteReplica(clusterState, message, results, onComplete);

  }

  void deleteCoreNode(String collectionName, String replicaName, Replica replica, String core) throws Exception {
    ZkNodeProps m = new ZkNodeProps(
        Overseer.QUEUE_OPERATION, OverseerAction.DELETECORE.toLower(),
        ZkStateReader.CORE_NAME_PROP, core,
        ZkStateReader.NODE_NAME_PROP, replica.getStr(ZkStateReader.NODE_NAME_PROP),
        ZkStateReader.COLLECTION_PROP, collectionName,
        ZkStateReader.CORE_NODE_NAME_PROP, replicaName,
        ZkStateReader.BASE_URL_PROP, replica.getStr(ZkStateReader.BASE_URL_PROP));
    overseer.offerStateUpdate(Utils.toJSON(m));
  }

  void checkRequired(ZkNodeProps message, String... props) {
    for (String prop : props) {
      if(message.get(prop) == null){
        throw new SolrException(ErrorCode.BAD_REQUEST, StrUtils.join(Arrays.asList(props),',') +" are required params" );
      }
    }

  }

  void checkResults(String label, NamedList<Object> results, boolean failureIsFatal) throws SolrException {
    Object failure = results.get("failure");
    if (failure == null) {
      failure = results.get("error");
    }
    if (failure != null) {
      String msg = "Error: " + label + ": " + Utils.toJSONString(results);
      if (failureIsFatal) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, msg);
      } else {
        log.error(msg);
      }
    }
  }

  @SuppressWarnings({"unchecked"})
  void commit(@SuppressWarnings({"rawtypes"})NamedList results, String slice, Replica parentShardLeader) {
    log.debug("Calling soft commit to make sub shard updates visible");
    String coreUrl = new ZkCoreNodeProps(parentShardLeader).getCoreUrl();
    // HttpShardHandler is hard coded to send a QueryRequest hence we go direct
    // and we force open a searcher so that we have documents to show upon switching states
    UpdateResponse updateResponse = null;
    try {
      updateResponse = softCommit(coreUrl, overseer.getCoreContainer().getUpdateShardHandler().getTheSharedHttpClient());
      processResponse(results, null, coreUrl, updateResponse, slice, Collections.emptySet());
    } catch (Exception e) {
      ParWork.propagateInterrupt(e);
      processResponse(results, e, coreUrl, updateResponse, slice, Collections.emptySet());
      throw new SolrException(ErrorCode.SERVER_ERROR, "Unable to call distrib softCommit on: " + coreUrl, e);
    }
  }


  static UpdateResponse softCommit(String url, Http2SolrClient httpClient) throws SolrServerException, IOException {
    UpdateRequest ureq = new UpdateRequest();
    ureq.setBasePath(url);
    ureq.setParams(new ModifiableSolrParams());
    ureq.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, true, true);
    return ureq.process(httpClient);
  }

  static String waitForCoreNodeName(ZkStateReader zkStateReader, String collectionName, String msgNodeName, String msgCore) {
    AtomicReference<String> errorMessage = new AtomicReference<>();
    AtomicReference<String> coreNodeName = new AtomicReference<>();
    try {
      zkStateReader.waitForState(collectionName, 15, TimeUnit.SECONDS, (n, c) -> {
        if (c == null)
          return false;
        final Map<String,Slice> slicesMap = c.getSlicesMap();
        for (Slice slice : slicesMap.values()) {
          for (Replica replica : slice.getReplicas()) {

            String nodeName = replica.getStr(ZkStateReader.NODE_NAME_PROP);
            String core = replica.getStr(ZkStateReader.CORE_NAME_PROP);

            if (msgNodeName.equals(nodeName) && core.equals(msgCore)) {
              coreNodeName.set(replica.getName());
              return true;
            }
          }
        }
        return false;
      });
    } catch (TimeoutException e) {
      String error = errorMessage.get();
      if (error == null)
        error = "Timeout waiting for collection state.";
      throw new ZkController.NotInClusterStateException(ErrorCode.SERVER_ERROR, error);
    } catch (InterruptedException e) {
      ParWork.propagateInterrupt(e);
      throw new SolrException(ErrorCode.SERVER_ERROR, "Interrupted", e);
    }

    return coreNodeName.get();
  }

  void waitForNewShard(String collectionName, String sliceName) {
    log.debug("Waiting for slice {} of collection {} to be available", sliceName, collectionName);
    try {
      zkStateReader.waitForState(collectionName, 30, TimeUnit.SECONDS, (n, c) -> {
        if (c == null)
          return false;
        Slice slice = c.getSlice(sliceName);
        if (slice != null) {
          return true;
        }
        return false;
      });
    } catch (TimeoutException e) {
      String error = "Timeout waiting for new shard.";
      throw new ZkController.NotInClusterStateException(ErrorCode.SERVER_ERROR, error);
    } catch (InterruptedException e) {
      ParWork.propagateInterrupt(e);
      throw new SolrException(ErrorCode.SERVER_ERROR, "Interrupted", e);
    }
  }

  DocRouter.Range intersect(DocRouter.Range a, DocRouter.Range b) {
    if (a == null || b == null || !a.overlaps(b)) {
      return null;
    } else if (a.isSubsetOf(b))
      return a;
    else if (b.isSubsetOf(a))
      return b;
    else if (b.includes(a.max)) {
      return new DocRouter.Range(b.min, a.max);
    } else  {
      return new DocRouter.Range(a.min, b.max);
    }
  }

  void addPropertyParams(ZkNodeProps message, ModifiableSolrParams params) {
    // Now add the property.key=value pairs
    for (String key : message.keySet()) {
      if (key.startsWith(COLL_PROP_PREFIX)) {
        params.set(key, message.getStr(key));
      }
    }
  }

  void addPropertyParams(ZkNodeProps message, Map<String, Object> map) {
    // Now add the property.key=value pairs
    for (String key : message.keySet()) {
      if (key.startsWith(COLL_PROP_PREFIX)) {
        map.put(key, message.getStr(key));
      }
    }
  }


  private void modifyCollection(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results)
      throws Exception {

    final String collectionName = message.getStr(ZkStateReader.COLLECTION_PROP);
    //the rest of the processing is based on writing cluster state properties
    //remove the property here to avoid any errors down the pipeline due to this property appearing
    String configName = (String) message.getProperties().remove(CollectionAdminParams.COLL_CONF);

    if(configName != null) {
      validateConfigOrThrowSolrException(configName);

      createConfNode(cloudManager.getDistribStateManager(), configName, collectionName);
      reloadCollection(null, new ZkNodeProps(NAME, collectionName), results);
    }

    overseer.offerStateUpdate(Utils.toJSON(message));

    try {
      zkStateReader.waitForState(collectionName, 30, TimeUnit.SECONDS, (n, c) -> {
        if (c == null) return false;

        for (Map.Entry<String,Object> updateEntry : message.getProperties().entrySet()) {
          String updateKey = updateEntry.getKey();

          if (!updateKey.equals(ZkStateReader.COLLECTION_PROP)
                  && !updateKey.equals(Overseer.QUEUE_OPERATION)
                  && updateEntry.getValue() != null // handled below in a separate conditional
                  && !updateEntry.getValue().equals(c.get(updateKey))) {
            return false;
          }

          if (updateEntry.getValue() == null && c.containsKey(updateKey)) {
            return false;
          }
        }
        return true;
      });
    } catch (TimeoutException | InterruptedException e) {
      ParWork.propagateInterrupt(e);
      log.error("modifyCollection(ClusterState=" + clusterState + ", ZkNodeProps=" + message + ", NamedList=" + results + ")", e);
      throw new SolrException(ErrorCode.SERVER_ERROR, "Could not modify collection " + message, e);
    }

    // if switching to/from read-only mode reload the collection
    if (message.keySet().contains(ZkStateReader.READ_ONLY)) {
      reloadCollection(null, new ZkNodeProps(NAME, collectionName), results);
    }
  }

  void cleanupCollection(String collectionName, @SuppressWarnings({"rawtypes"})NamedList results) throws Exception {
    log.error("Cleaning up collection [{}].", collectionName);
    Map<String, Object> props = makeMap(
        Overseer.QUEUE_OPERATION, DELETE.toLower(),
        NAME, collectionName);
    commandMap.get(DELETE).call(zkStateReader.getClusterState(), new ZkNodeProps(props), results);
    zkStateReader.waitForState(collectionName, 10, TimeUnit.SECONDS, (liveNodes, collectionState) -> collectionState == null);
  }

  Map<String, Replica> waitToSeeReplicasInState(String collectionName, Collection<String> coreUrls, boolean requireActive) {
    log.info("wait to see {} in clusterstate {}", coreUrls, zkStateReader.getClusterState().getCollection(collectionName));
    assert coreUrls.size() > 0;

    AtomicReference<Map<String, Replica>> result = new AtomicReference<>();
    AtomicReference<String> errorMessage = new AtomicReference<>();
    try {
      zkStateReader.waitForState(collectionName, 10, TimeUnit.SECONDS, (n, c) -> { // TODO config timeout up for prod, down for non nightly tests
        if (c == null)
          return false;
        Map<String, Replica> r = new HashMap<>();
        for (String coreUrl : coreUrls) {
          if (r.containsKey(coreUrl)) continue;
          Collection<Slice> slices = c.getSlices();
          if (slices != null) {
            for (Slice slice : slices) {
              for (Replica replica : slice.getReplicas()) {
                if (coreUrl.equals(replica.getCoreUrl()) && ((requireActive ? replica.getState().equals(Replica.State.ACTIVE) : true)
                        && zkStateReader.getClusterState().liveNodesContain(replica.getNodeName()))) {
                  r.put(coreUrl, replica);
                  break;
                }
              }
            }
          }
        }

        if (r.size() == coreUrls.size()) {
          result.set(r);
          return true;
        } else {
          errorMessage.set("Timed out waiting to see all replicas: " + coreUrls + " in cluster state. Last state: " + c);
          return false;
        }

      });
    } catch (TimeoutException e) {
      String error = errorMessage.get();
      if (error == null)
        error = "Timeout waiting for collection state.";
      throw new SolrException(ErrorCode.SERVER_ERROR, error);
    } catch (InterruptedException e) {
      ParWork.propagateInterrupt(e);
      throw new SolrException(ErrorCode.SERVER_ERROR, "Interrupted", e);
    }
    return result.get();
  }

  List<ZkNodeProps> addReplica(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results, Runnable onComplete)
      throws Exception {

    return ((AddReplicaCmd) commandMap.get(ADDREPLICA)).addReplica(clusterState, message, results, onComplete);
  }

  void validateConfigOrThrowSolrException(String configName) throws IOException, KeeperException, InterruptedException {
    boolean isValid = cloudManager.getDistribStateManager().hasData(ZkConfigManager.CONFIGS_ZKNODE + "/" + configName);
    if(!isValid) {
      overseer.getZkStateReader().getZkClient().printLayout();
      throw new SolrException(ErrorCode.BAD_REQUEST, "Can not find the specified config set: " + configName);
    }
  }

  /**
   * This doesn't validate the config (path) itself and is just responsible for creating the confNode.
   * That check should be done before the config node is created.
   */
  public static void createConfNode(DistribStateManager stateManager, String configName, String coll) throws IOException, AlreadyExistsException, BadVersionException, KeeperException, InterruptedException {

    if (configName != null) {
      String collDir = ZkStateReader.COLLECTIONS_ZKNODE + "/" + coll;
      log.debug("creating collections conf node {} ", collDir);
      byte[] data = Utils.toJSON(makeMap(ZkController.CONFIGNAME_PROP, configName));
      if (stateManager.hasData(collDir)) {
        stateManager.setData(collDir, data, -1);
      } else {
        stateManager.makePath(collDir, data, CreateMode.PERSISTENT, false);
      }
    } else {
      throw new SolrException(ErrorCode.BAD_REQUEST,"Unable to get config name");
    }
  }

  private List<Replica> collectionCmd(ZkNodeProps message, ModifiableSolrParams params,
                             NamedList<Object> results, Replica.State stateMatcher, String asyncId) throws KeeperException, InterruptedException {
    return collectionCmd( message, params, results, stateMatcher, asyncId, Collections.emptySet());
  }

  /**
   * Send request to all replicas of a collection
   * @return List of replicas which is not live for receiving the request
   */
  List<Replica> collectionCmd(ZkNodeProps message, ModifiableSolrParams params,
                     NamedList<Object> results, Replica.State stateMatcher, String asyncId, Set<String> okayExceptions) throws KeeperException, InterruptedException {
    log.info("Executing Collection Cmd={}, asyncId={}", params, asyncId);
    String collectionName = message.getStr(NAME);
    @SuppressWarnings("deprecation")
    ShardHandler shardHandler = shardHandlerFactory.getShardHandler(overseer.getCoreContainer().getUpdateShardHandler().getTheSharedHttpClient());

    ClusterState clusterState = zkStateReader.getClusterState();
    DocCollection coll = clusterState.getCollectionOrNull(collectionName);
    if (coll == null) return null;
    List<Replica> notLivesReplicas = new ArrayList<>();
    final ShardRequestTracker shardRequestTracker = new ShardRequestTracker(asyncId, adminPath, zkStateReader, shardHandlerFactory, overseer);
    for (Slice slice : coll.getSlices()) {
      notLivesReplicas.addAll(shardRequestTracker.sliceCmd(clusterState, params, stateMatcher, slice, shardHandler));
    }

    shardRequestTracker.processResponses(results, shardHandler, false, null, okayExceptions);
    return notLivesReplicas;
  }

  private static void processResponse(NamedList<Object> results, ShardResponse srsp, Set<String> okayExceptions) {
    Throwable e = srsp.getException();
    String nodeName = srsp.getNodeName();
    SolrResponse solrResponse = srsp.getSolrResponse();
    String shard = srsp.getShard();

    processResponse(results, e, nodeName, solrResponse, shard, okayExceptions);
  }

  @SuppressWarnings("deprecation")
  private static void processResponse(NamedList<Object> results, Throwable e, String nodeName, SolrResponse solrResponse, String shard, Set<String> okayExceptions) {
    String rootThrowable = null;
    if (e instanceof BaseHttpSolrClient.RemoteSolrException) {
      rootThrowable = ((BaseHttpSolrClient.RemoteSolrException) e).getRootThrowable();
    }

    if (e != null && (rootThrowable == null || !okayExceptions.contains(rootThrowable))) {
      log.error("Error from shard: {}", shard, e);
      addFailure(results, nodeName, e.getClass().getName() + ":" + e.getMessage());
    } else {
      addSuccess(results, nodeName, solrResponse.getResponse());
    }
  }

  @SuppressWarnings("unchecked")
  private static void addFailure(NamedList<Object> results, String key, Object value) {
    SimpleOrderedMap<Object> failure = (SimpleOrderedMap<Object>) results.get("failure");
    if (failure == null) {
      failure = new SimpleOrderedMap<>();
      results.add("failure", failure);
    }
    failure.add(key, value);
  }

  @SuppressWarnings("unchecked")
  private static void addSuccess(NamedList<Object> results, String key, Object value) {
    SimpleOrderedMap<Object> success = (SimpleOrderedMap<Object>) results.get("success");
    if (success == null) {
      success = new SimpleOrderedMap<>();
      results.add("success", success);
    }
    success.add(key, value);
  }

  private static NamedList<Object> waitForCoreAdminAsyncCallToComplete(String nodeName, String requestId, String adminPath, ZkStateReader zkStateReader, HttpShardHandlerFactory shardHandlerFactory, Overseer overseer) throws KeeperException, InterruptedException {
    ShardHandler shardHandler = shardHandlerFactory.getShardHandler(overseer.getCoreContainer().getUpdateShardHandler().getTheSharedHttpClient());
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CoreAdminParams.ACTION, CoreAdminAction.REQUESTSTATUS.toString());
    params.set(CoreAdminParams.REQUESTID, requestId);
    int counter = 0;
    ShardRequest sreq;

      sreq = new ShardRequest();
      params.set("qt", adminPath);
      sreq.purpose = 1;
      String replica = zkStateReader.getBaseUrlForNodeName(nodeName);
      sreq.shards = new String[]{replica};
      sreq.actualShards = sreq.shards;
      sreq.params = params;
      CountDownLatch latch = new CountDownLatch(1);

      // mn- from DistributedMap
      final String asyncPathToWaitOn = Overseer.OVERSEER_ASYNC_IDS + "/mn-" + requestId;

      Watcher waitForAsyncId = new Watcher() {
        @Override
        public void process(WatchedEvent event) {
          if (Watcher.Event.EventType.None.equals(event.getType())) {
            return;
          }
          if (event.getType().equals(Watcher.Event.EventType.NodeCreated)) {
            latch.countDown();
          } else if (event.getType().equals(Event.EventType.NodeDeleted)) {
            // no-op: gets deleted below once we're done with it
            return;
          }

          Stat rstats2 = null;
          try {
            rstats2 = zkStateReader.getZkClient().exists(asyncPathToWaitOn, this);
          } catch (KeeperException e) {
            log.error("ZooKeeper exception", e);
            return;
          } catch (InterruptedException e) {
            log.info("interrupted");
            return;
          }
          if (rstats2 != null) {
            latch.countDown();
          }

        }
      };

      Stat rstats = zkStateReader.getZkClient().exists(asyncPathToWaitOn, waitForAsyncId);

      if (rstats != null) {
        latch.countDown();
      }

      latch.await(15, TimeUnit.SECONDS); // nocommit - still need a central timeout strat

      shardHandler.submit(sreq, replica, sreq.params);

      ShardResponse srsp;

      srsp = shardHandler.takeCompletedOrError();
      if (srsp != null) {
        NamedList<Object> results = new NamedList<>();
        processResponse(results, srsp, Collections.emptySet());
        if (srsp.getSolrResponse().getResponse() == null) {
          NamedList<Object> response = new NamedList<>();
          response.add("STATUS", "failed");
          return response;
        }

        String r = (String) srsp.getSolrResponse().getResponse().get("STATUS");
        if (r.equals("running")) {
          if (log.isDebugEnabled())  log.debug("The task is still RUNNING, continuing to wait.");
          throw new SolrException(ErrorCode.BAD_REQUEST, "Task is still running even after reporting complete requestId: " + requestId + "" + srsp.getSolrResponse().getResponse().get("STATUS") +
                  "retried " + counter + "times");
        } else if (r.equals("completed")) {
          // we're done with this entry in the DistributeMap
          overseer.getCoreContainer().getZkController().clearAsyncId(requestId);
          if (log.isDebugEnabled()) log.debug("The task is COMPLETED, returning");
          return srsp.getSolrResponse().getResponse();
        } else if (r.equals("failed")) {
          // TODO: Improve this. Get more information.
          if (log.isDebugEnabled()) log.debug("The task is FAILED, returning");

        } else if (r.equals("notfound")) {
          if (log.isDebugEnabled()) log.debug("The task is notfound, retry");
          throw new SolrException(ErrorCode.BAD_REQUEST, "Invalid status request for requestId: " + requestId + "" + srsp.getSolrResponse().getResponse().get("STATUS") +
                  "retried " + counter + "times");
        } else {
          throw new SolrException(ErrorCode.BAD_REQUEST, "Invalid status request " + srsp.getSolrResponse().getResponse().get("STATUS"));
        }
      }

    throw new SolrException(ErrorCode.SERVER_ERROR, "No response on request for async status");
  }

  @Override
  public String getName() {
    return "Overseer Collection Message Handler";
  }

  @Override
  public String getTimerName(String operation) {
    return "collection_" + operation;
  }

  @Override
  public String getTaskKey(ZkNodeProps message) {
    return message.containsKey(COLLECTION_PROP) ?
      message.getStr(COLLECTION_PROP) : message.getStr(NAME);
  }


  private long sessionId = -1;
  private LockTree.Session lockSession;

  @Override
  public Lock lockTask(ZkNodeProps message, OverseerTaskProcessor.TaskBatch taskBatch) {
    if (lockSession == null || sessionId != taskBatch.getId()) {
      //this is always called in the same thread.
      //Each batch is supposed to have a new taskBatch
      //So if taskBatch changes we must create a new Session
      // also check if the running tasks are empty. If yes, clear lockTree
      // this will ensure that locks are not 'leaked'
      if(taskBatch.getRunningTasks() == 0) lockTree.clear();
      lockSession = lockTree.getSession();
    }
    return lockSession.lock(getCollectionAction(message.getStr(Overseer.QUEUE_OPERATION)),
        Arrays.asList(
            getTaskKey(message),
            message.getStr(ZkStateReader.SHARD_ID_PROP),
            message.getStr(ZkStateReader.REPLICA_PROP))

    );
  }


  @Override
  public void close() throws IOException {
    this.isClosed = true;
    try {
      cloudManager.close();
    } catch (NullPointerException e) {
      // okay
    }
    assert ObjectReleaseTracker.release(this);
  }

  @Override
  public boolean isClosed() {
    return isClosed;
  }

  protected interface Cmd {
    void call(ClusterState state, ZkNodeProps message, NamedList results) throws Exception;
  }

  /*
   * backward compatibility reasons, add the response with the async ID as top level.
   * This can be removed in Solr 9
   */
  @Deprecated
  static boolean INCLUDE_TOP_LEVEL_RESPONSE = true;

  public ShardRequestTracker syncRequestTracker() {
    return new ShardRequestTracker(null, adminPath, zkStateReader, shardHandlerFactory, overseer);
  }

  public ShardRequestTracker asyncRequestTracker(String asyncId) {
    return new ShardRequestTracker(asyncId, adminPath, zkStateReader, shardHandlerFactory, overseer);
  }

  public static class ShardRequestTracker{
    private final String asyncId;
    private final NamedList<String> shardAsyncIdByNode = new NamedList<String>();
    private final String adminPath;
    private final ZkStateReader zkStateReader;
    private final HttpShardHandlerFactory shardHandlerFactory;
    private final Overseer overseer;

    private ShardRequestTracker(String asyncId, String adminPath, ZkStateReader reader,  HttpShardHandlerFactory shardHandlerFactory,  Overseer overseer) {
      this.asyncId = asyncId;
      this.adminPath = adminPath;
      this.zkStateReader = reader;
      this.shardHandlerFactory = shardHandlerFactory;
      this.overseer = overseer;
    }

    /**
     * Send request to all replicas of a slice
     * @return List of replicas which is not live for receiving the request
     */
    public List<Replica> sliceCmd(ClusterState clusterState, ModifiableSolrParams params, Replica.State stateMatcher,
                  Slice slice, ShardHandler shardHandler) {
      List<Replica> notLiveReplicas = new ArrayList<>();
      for (Replica replica : slice.getReplicas()) {
        if ((stateMatcher == null || Replica.State.getState(replica.getStr(ZkStateReader.STATE_PROP)) == stateMatcher)) {
          if (clusterState.liveNodesContain(replica.getStr(ZkStateReader.NODE_NAME_PROP))) {
            // For thread safety, only simple clone the ModifiableSolrParams
            ModifiableSolrParams cloneParams = new ModifiableSolrParams();
            cloneParams.add(params);
            cloneParams.set(CoreAdminParams.CORE, replica.getStr(ZkStateReader.CORE_NAME_PROP));

            sendShardRequest(replica.getStr(ZkStateReader.NODE_NAME_PROP), cloneParams, shardHandler);
          } else {
            notLiveReplicas.add(replica);
          }
        }
      }
      return notLiveReplicas;
    }

    public void sendShardRequest(String nodeName, ModifiableSolrParams params,
        ShardHandler shardHandler) {
      sendShardRequest(nodeName, params, shardHandler, adminPath, zkStateReader);
    }

    public void sendShardRequest(String nodeName, ModifiableSolrParams params, ShardHandler shardHandler,
        String adminPath, ZkStateReader zkStateReader) {
      if (asyncId != null) {
        String coreAdminAsyncId = asyncId + Math.abs(System.nanoTime());
        params.set(ASYNC, coreAdminAsyncId);
        track(nodeName, coreAdminAsyncId);
      }

      ShardRequest sreq = new ShardRequest();
      params.set("qt", adminPath);
      sreq.purpose = 1;
      String replica = zkStateReader.getBaseUrlForNodeName(nodeName);
      sreq.shards = new String[] {replica};
      sreq.actualShards = sreq.shards;
      sreq.nodeName = nodeName;
      sreq.params = params;

      shardHandler.submit(sreq, replica, sreq.params);
    }

    void processResponses(NamedList<Object> results, ShardHandler shardHandler, boolean abortOnError, String msgOnError) throws KeeperException, InterruptedException {
      processResponses(results, shardHandler, abortOnError, msgOnError, Collections.emptySet());
    }

    void processResponses(NamedList<Object> results, ShardHandler shardHandler, boolean abortOnError, String msgOnError,
        Set<String> okayExceptions) throws KeeperException, InterruptedException {
      // Processes all shard responses
      ShardResponse srsp;
      do {
        srsp = shardHandler.takeCompletedOrError();
        if (srsp != null) {
          processResponse(results, srsp, okayExceptions);
          Throwable exception = srsp.getException();
          if (abortOnError && exception != null) {
            // drain pending requests
            while (srsp != null) {
              srsp = shardHandler.takeCompletedOrError();
            }
            throw new SolrException(ErrorCode.SERVER_ERROR, msgOnError, exception);
          }
        }
      } while (srsp != null);

      // If request is async wait for the core admin to complete before returning
      if (asyncId != null) {
        waitForAsyncCallsToComplete(results); // TODO: Shouldn't we abort with msgOnError exception when failure?
        shardAsyncIdByNode.clear();
      }
    }

    private void waitForAsyncCallsToComplete(NamedList<Object> results) throws KeeperException, InterruptedException {
      for (Map.Entry<String,String> nodeToAsync:shardAsyncIdByNode) {
        final String node = nodeToAsync.getKey();
        final String shardAsyncId = nodeToAsync.getValue();
        log.debug("I am Waiting for :{}/{}", node, shardAsyncId);
        NamedList<Object> reqResult = waitForCoreAdminAsyncCallToComplete(node, shardAsyncId, adminPath, zkStateReader, shardHandlerFactory, overseer);
        if (INCLUDE_TOP_LEVEL_RESPONSE) {
          results.add(shardAsyncId, reqResult);
        }
        if ("failed".equalsIgnoreCase(((String)reqResult.get("STATUS")))) {
          log.error("Error from shard {}: {}", node,  reqResult);
          addFailure(results, node, reqResult);
        } else {
          addSuccess(results, node, reqResult);
        }
      }
    }

    /** @deprecated consider to make it private after {@link CreateCollectionCmd} refactoring*/
    @Deprecated void track(String nodeName, String coreAdminAsyncId) {
      shardAsyncIdByNode.add(nodeName, coreAdminAsyncId);
    }
  }
}
