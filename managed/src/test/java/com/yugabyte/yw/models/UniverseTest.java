// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.models;

import com.fasterxml.jackson.databind.JsonNode;

import com.google.common.collect.Sets;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.cloud.UniverseResourceDetails;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;

import java.util.*;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class UniverseTest extends FakeDBApplication {
  private Provider defaultProvider;
  private Customer defaultCustomer;

  @Before
  public void setUp() {
    defaultCustomer = ModelFactory.testCustomer();
    defaultProvider = ModelFactory.awsProvider(defaultCustomer);
  }

  @Test
  public void testCreate() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    assertNotNull(u);
    assertThat(u.universeUUID, is(allOf(notNullValue(), equalTo(u.universeUUID))));
    assertThat(u.version, is(allOf(notNullValue(), equalTo(1))));
    assertThat(u.name, is(allOf(notNullValue(), equalTo("Test Universe"))));
    assertThat(u.getUniverseDetails(), is(notNullValue()));
  }

  @Test
  public void testGetSingleUniverse() {
    Universe newUniverse = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    assertNotNull(newUniverse);
    Universe fetchedUniverse = Universe.get(newUniverse.universeUUID);
    assertNotNull(fetchedUniverse);
    assertEquals(fetchedUniverse, newUniverse);
  }

  @Test
  public void testCheckIfUniverseExists() {
    Universe newUniverse = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    assertNotNull(newUniverse);
    assertThat(Universe.checkIfUniverseExists("Test Universe"), equalTo(true));
    assertThat(Universe.checkIfUniverseExists("Fake Universe"), equalTo(false));
  }

  @Test
  public void testGetMultipleUniverse() {
    Universe u1 = Universe.create("Universe1", UUID.randomUUID(), defaultCustomer.getCustomerId());
    Universe u2 = Universe.create("Universe2", UUID.randomUUID(), defaultCustomer.getCustomerId());
    Universe u3 = Universe.create("Universe3", UUID.randomUUID(), defaultCustomer.getCustomerId());
    Set<UUID> uuids = Sets.newHashSet(u1.universeUUID, u2.universeUUID, u3.universeUUID);

    Set<Universe> universes = Universe.get(uuids);
    assertNotNull(universes);
    assertEquals(universes.size(), 3);
  }

  @Test(expected = RuntimeException.class)
  public void testGetUnknownUniverse() {
    UUID unknownUUID = UUID.randomUUID();
    Universe u = Universe.get(unknownUUID);
  }

  @Test
  public void testSaveDetails() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());

    Universe.UniverseUpdater updater = new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        universeDetails = new UniverseDefinitionTaskParams();
        universeDetails.userIntent = new UserIntent();

        // Create some subnets.
        List<String> subnets = new ArrayList<String>();
        subnets.add("subnet-1");
        subnets.add("subnet-2");
        subnets.add("subnet-3");

        // Add a desired number of nodes.
        universeDetails.userIntent.numNodes = 5;
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        for (int idx = 1; idx <= universeDetails.userIntent.numNodes; idx++) {
          NodeDetails node = new NodeDetails();
          node.nodeName = "host-n" + idx;
          node.cloudInfo = new CloudSpecificInfo();
          node.cloudInfo.cloud = "aws";
          node.cloudInfo.az = "az-" + idx;
          node.cloudInfo.region = "test-region";
          node.cloudInfo.subnet_id = subnets.get(idx % subnets.size());
          node.cloudInfo.private_ip = "host-n" + idx;
          node.isTserver = true;
          if (idx <= 3) {
            node.isMaster = true;
          }
          node.nodeIdx = idx;
          universeDetails.nodeDetailsSet.add(node);
        }
        universe.setUniverseDetails(universeDetails);
      }
    };
    u = Universe.saveDetails(u.universeUUID, updater);

    int nodeIdx;
    for (NodeDetails node : u.getMasters()) {
      assertTrue(node.isMaster);
      assertNotNull(node.nodeName);
      nodeIdx = Character.getNumericValue(node.nodeName.charAt(node.nodeName.length() - 1));
      assertTrue(nodeIdx <= 3);
    }

    for (NodeDetails node : u.getTServers()) {
      assertTrue(node.isTserver);
      assertNotNull(node.nodeName);
      nodeIdx = Character.getNumericValue(node.nodeName.charAt(node.nodeName.length() - 1));
      assertTrue(nodeIdx <= 5);
    }

    assertTrue(u.getTServers().size() > u.getMasters().size());
    assertEquals(u.getMasters().size(), 3);
    assertEquals(u.getTServers().size(), 5);
  }

  @Test
  public void testVerifyIsTrue() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    List<NodeDetails> masters = new LinkedList<>();
    NodeDetails mockNode1 = mock(NodeDetails.class);
    masters.add(mockNode1);
    NodeDetails mockNode2 = mock(NodeDetails.class);
    masters.add(mockNode2);
    when(mockNode1.isQueryable()).thenReturn(true);
    when(mockNode2.isQueryable()).thenReturn(true);
    assertTrue(u.verifyMastersAreQueryable(masters));
  }

  @Test
  public void testMastersListEmptyVerifyIsFalse() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    assertFalse(u.verifyMastersAreQueryable(null));
    List<NodeDetails> masters = new LinkedList<>();
    assertFalse(u.verifyMastersAreQueryable(masters));
  }

  @Test
  public void testMastersInBadStateVerifyIsFalse() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    List<NodeDetails> masters = new LinkedList<>();
    NodeDetails mockNode1 = mock(NodeDetails.class);
    masters.add(mockNode1);
    NodeDetails mockNode2 = mock(NodeDetails.class);
    masters.add(mockNode2);
    when(mockNode1.isQueryable()).thenReturn(false);
    when(mockNode2.isQueryable()).thenReturn(true);
    assertFalse(u.verifyMastersAreQueryable(masters));
    when(mockNode1.isQueryable()).thenReturn(true);
    when(mockNode2.isQueryable()).thenReturn(false);
    assertFalse(u.verifyMastersAreQueryable(masters));
  }

  @Test
  public void testGetMasterAddresses() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());

    Universe.UniverseUpdater updater = new Universe.UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        universeDetails = new UniverseDefinitionTaskParams();
        universeDetails.userIntent = new UserIntent();

        // Add a desired number of nodes.
        universeDetails.userIntent.numNodes = 3;
        universeDetails.nodeDetailsSet = new HashSet<NodeDetails>();
        for (int idx = 1; idx <= universeDetails.userIntent.numNodes; idx++) {
          NodeDetails node = new NodeDetails();
          node.nodeName = "host-n" + idx;
          node.cloudInfo = new CloudSpecificInfo();
          node.cloudInfo.cloud = "aws";
          node.cloudInfo.az = "az-" + idx;
          node.cloudInfo.region = "test-region";
          node.cloudInfo.subnet_id = "subnet-" + idx;
          node.cloudInfo.private_ip = "host-n" + idx;
          node.state = NodeDetails.NodeState.Running;
          node.isTserver = true;
          if (idx <= 3) {
            node.isMaster = true;
          }
          node.nodeIdx = idx;
          universeDetails.nodeDetailsSet.add(node);
        }
        universe.setUniverseDetails(universeDetails);
      }
    };
    u = Universe.saveDetails(u.universeUUID, updater);
    String masterAddrs = u.getMasterAddresses();
    assertNotNull(masterAddrs);
    for (int idx = 1; idx <= 3; idx++) {
      assertThat(masterAddrs, containsString("host-n" + idx));
    }
  }

  @Test
  public void testGetMasterAddressesFails() {
    Universe u = spy(Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId()));
    when(u.verifyMastersAreQueryable(anyList())).thenReturn(false);
    assertEquals("", u.getMasterAddresses());
  }

  @Test
  public void testToJSONSuccess() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(),
        defaultCustomer.getCustomerId());

    // Create regions
    Region r1 = Region.create(defaultProvider, "region-1", "Region 1", "yb-image-1");
    Region r2 = Region.create(defaultProvider, "region-2", "Region 2", "yb-image-1");
    Region r3 = Region.create(defaultProvider, "region-3", "Region 3", "yb-image-1");
    List<UUID> regionList = new ArrayList<>();
    regionList.add(r1.uuid);
    regionList.add(r2.uuid);
    regionList.add(r3.uuid);

    // Add non-EBS instance type with price to each region
    String instanceType = "c3.xlarge";
    double instancePrice = 0.1;
    InstanceType.upsert(defaultProvider.code, instanceType, 1, 20.0, null);
    PriceComponent.PriceDetails instanceDetails = new PriceComponent.PriceDetails();
    instanceDetails.pricePerHour = instancePrice;
    PriceComponent.upsert(defaultProvider.code, r1.code, instanceType, instanceDetails);
    PriceComponent.upsert(defaultProvider.code, r2.code, instanceType, instanceDetails);
    PriceComponent.upsert(defaultProvider.code, r3.code, instanceType, instanceDetails);

    // Create userIntent
    UserIntent userIntent = new UserIntent();
    userIntent.isMultiAZ = true;
    userIntent.replicationFactor = 3;
    userIntent.regionList = regionList;
    userIntent.instanceType = instanceType;
    userIntent.provider = defaultProvider.uuid.toString();
    userIntent.deviceInfo = new DeviceInfo();
    userIntent.deviceInfo.ebsType = PublicCloudConstants.EBSType.IO1;
    userIntent.deviceInfo.numVolumes = 2;
    userIntent.deviceInfo.diskIops = 1000;
    userIntent.deviceInfo.volumeSize = 100;

    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater(userIntent));

    JsonNode universeJson = u.toJson();
    assertThat(universeJson.get("universeUUID").asText(), allOf(notNullValue(),
        equalTo(u.universeUUID.toString())));
    JsonNode resources = Json.toJson(UniverseResourceDetails.create(u.getNodes(),
        u.getUniverseDetails()));
    assertThat(universeJson.get("resources").asText(), allOf(notNullValue(),
        equalTo(resources.asText())));
    JsonNode userIntentJson = universeJson.get("universeDetails").get("userIntent");
    assertTrue(userIntentJson.get("regionList").isArray());
    assertEquals(3, userIntentJson.get("regionList").size());
    JsonNode masterGFlags = userIntentJson.get("masterGFlags");
    assertThat(masterGFlags, is(notNullValue()));
    assertTrue(masterGFlags.isObject());

    JsonNode regionsNode = universeJson.get("regions");
    assertThat(regionsNode, is(notNullValue()));
    assertTrue(regionsNode.isArray());
    assertEquals(3, regionsNode.size());

    JsonNode providerNode = universeJson.get("provider");
    assertThat(providerNode, is(notNullValue()));
    assertThat(providerNode.get("uuid").asText(), allOf(notNullValue(),
        equalTo(defaultProvider.uuid.toString())));
  }

  @Test
  public void testToJSONWithNullRegionList() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(),
        defaultCustomer.getCustomerId());
    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());

    JsonNode universeJson = u.toJson();
    assertThat(universeJson.get("universeUUID").asText(), allOf(notNullValue(),
        equalTo(u.universeUUID.toString())));
    JsonNode userIntentJson = universeJson.get("universeDetails").get("userIntent");
    assertTrue(userIntentJson.get("regionList").isNull());
    JsonNode regionsNode = universeJson.get("regions");
    assertNull(regionsNode);
    JsonNode providerNode = universeJson.get("provider");
    assertNull(providerNode);
  }

  @Test
  public void testToJSONWithNullGFlags() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(),
        defaultCustomer.getCustomerId());
    UserIntent userIntent = new UserIntent();
    userIntent.isMultiAZ = true;
    userIntent.replicationFactor = 3;
    userIntent.regionList = new ArrayList<>();
    userIntent.masterGFlags = null;

    // SaveDetails in order to generate universeDetailsJson with null gflags
    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater(userIntent));

    // Update in-memory user intent so userDetails no longer has null gflags, but json still does
    UniverseDefinitionTaskParams udtp = u.getUniverseDetails();
    udtp.userIntent.masterGFlags = new HashMap<>();
    u.setUniverseDetails(udtp);

    // Verify returned json is generated from the non-json userDetails object
    JsonNode universeJson = u.toJson();
    assertThat(universeJson.get("universeUUID").asText(), allOf(notNullValue(),
        equalTo(u.universeUUID.toString())));
    JsonNode userIntentJson = universeJson.get("universeDetails").get("userIntent");
    JsonNode masterGFlags = userIntentJson.get("masterGFlags");
    assertThat(masterGFlags, is(notNullValue()));
    assertTrue(masterGFlags.isObject());
    JsonNode providerNode = universeJson.get("provider");
    assertNull(providerNode);
  }

  @Test
  public void testToJSONWithEmptyRegionList() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());

    UserIntent userIntent = new UserIntent();
    userIntent.isMultiAZ = true;
    userIntent.replicationFactor = 3;
    userIntent.regionList = new ArrayList<>();

    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater(userIntent));

    JsonNode universeJson = u.toJson();
    assertThat(universeJson.get("universeUUID").asText(), allOf(notNullValue(), equalTo(u.universeUUID.toString())));
    JsonNode userIntentJson =
            universeJson.get("universeDetails").get("userIntent");
    assertTrue(userIntentJson.get("regionList").isArray());
    JsonNode regionsNode = universeJson.get("regions");
    assertNull(regionsNode);
    JsonNode providerNode = universeJson.get("provider");
    assertNull(providerNode);
  }

  @Test
  public void testToJSONOfGFlags() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());

    JsonNode universeJson = u.toJson();
    assertThat(universeJson.get("universeUUID").asText(),
               allOf(notNullValue(), equalTo(u.universeUUID.toString())));
    JsonNode univDetailsGflagsJson =
        universeJson.get("universeDetails").get("userIntent").get("masterGFlags");
    assertThat(univDetailsGflagsJson, is(notNullValue()));
    assertEquals(0, univDetailsGflagsJson.size());
  }

  @Test
  public void testFromJSONWithFlags() {
    Universe u = Universe.create("Test Universe", UUID.randomUUID(), defaultCustomer.getCustomerId());
    u = Universe.saveDetails(u.universeUUID, ApiUtils.mockUniverseUpdater());
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    taskParams.userIntent = getBaseIntent();
    Map<String, String> gflagMap = new HashMap<>();
    gflagMap.put("emulate_redis_responses", "false");
    taskParams.userIntent.masterGFlags = gflagMap;
    JsonNode universeJson = Json.toJson(taskParams);

    assertThat(universeJson.get("userIntent").get("masterGFlags").get("emulate_redis_responses"), is(notNullValue()));
  }

  private UserIntent getBaseIntent() {

    // Create regions
    Region r1 = Region.create(defaultProvider, "region-1", "Region 1", "yb-image-1");
    Region r2 = Region.create(defaultProvider, "region-2", "Region 2", "yb-image-1");
    Region r3 = Region.create(defaultProvider, "region-3", "Region 3", "yb-image-1");
    List<UUID> regionList = new ArrayList<>();
    regionList.add(r1.uuid);
    regionList.add(r2.uuid);
    regionList.add(r3.uuid);
    String instanceType = "c3.xlarge";
    // Create userIntent
    UserIntent userIntent = new UserIntent();
    userIntent.isMultiAZ = true;
    userIntent.replicationFactor = 3;
    userIntent.regionList = regionList;
    userIntent.instanceType = instanceType;
    userIntent.provider = defaultProvider.uuid.toString();
    userIntent.deviceInfo = new DeviceInfo();
    userIntent.deviceInfo.ebsType = PublicCloudConstants.EBSType.IO1;
    userIntent.deviceInfo.numVolumes = 2;
    userIntent.deviceInfo.diskIops = 1000;
    userIntent.deviceInfo.volumeSize = 100;
    return userIntent;
  }
}
