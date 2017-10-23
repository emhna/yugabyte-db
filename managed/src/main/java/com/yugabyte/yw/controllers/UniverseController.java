// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;


import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yugabyte.yw.commissioner.tasks.DestroyUniverse;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.forms.*;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.models.helpers.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.UniverseResourceDetails;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import org.yb.client.YBClient;
import play.data.Form;
import play.data.FormFactory;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.Results;

import static com.yugabyte.yw.common.PlacementInfoUtil.updatePlacementInfo;


public class UniverseController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(UniverseController.class);

  @Inject
  FormFactory formFactory;

  @Inject
  Commissioner commissioner;

  @Inject
  MetricQueryHelper metricQueryHelper;

  // The YB client to use.
  public YBClientService ybService;

  @Inject
  public UniverseController(YBClientService service) {
    this.ybService = service;
  }

  /**
   * API that checks if a Universe with a given name already exists.
   * @return true if universe already exists, false otherwise
   */
  public Result findByName(UUID customerUUID, String universeName) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    LOG.info("Finding Universe with name {}.", universeName);
    if (Universe.checkIfUniverseExists(universeName)) {
      return ApiResponse.error(BAD_REQUEST, "Universe already exists");
    } else {
      return ApiResponse.success("Universe does not Exist");
    }
  }

  /**
   * API that binds the UniverseDefinitionTaskParams class by merging
   * the UserIntent with the generated taskParams.
   * @param customerUUID the ID of the customer configuring the Universe.
   * @return UniverseDefinitionTasksParams in a serialized form
   */
  public Result configure(UUID customerUUID) {
    try {
      ObjectNode formData = (ObjectNode)request().body().asJson();
      UniverseDefinitionTaskParams taskParams = bindFormDataToTaskParams(formData);
      // Verify the customer with this universe is present.
      Customer customer = Customer.get(customerUUID);
      if (customer == null) {
        return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
      }
      PlacementInfoUtil.updateUniverseDefinition(taskParams, customer.getCustomerId());
      return ApiResponse.success(taskParams);
    } catch (Exception e) {
      LOG.error("Unable to Configure Universe for Customer with ID {} Failed with message: {}.",
                customerUUID, e);
      return ApiResponse.error(INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * API that calculates the resource estimate for the NodeDetailSet
   * @param customerUUID the ID of the Customer
   * @return the Result object containing the Resource JSON data.
   */
  public Result getUniverseResources(UUID customerUUID) {
    try {
      ObjectNode formData = (ObjectNode) request().body().asJson();
      UniverseDefinitionTaskParams taskParams = bindFormDataToTaskParams(formData);
      return ApiResponse.success(UniverseResourceDetails.create(taskParams.nodeDetailsSet,
          taskParams));
    } catch (Throwable t) {
      return ApiResponse.error(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  /**
   * API that queues a task to create a new universe. This does not wait for the creation.
   * @return result of the universe create operation.
   */
  public Result create(UUID customerUUID) {
    UniverseDefinitionTaskParams taskParams;
    try {
      LOG.info("Create for {}.", customerUUID);
      // Get the user submitted form data.
      ObjectNode formData = (ObjectNode) request().body().asJson();
      taskParams = bindFormDataToTaskParams(formData);
    } catch (Throwable t) {
      return ApiResponse.error(BAD_REQUEST, t.getMessage());
    }
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    try {
      // Set the provider code.
      Provider provider = Provider.find.byId(UUID.fromString(taskParams.userIntent.provider));
      String providerCode = provider.code;
      taskParams.userIntent.providerType = CloudType.valueOf(providerCode);
      updatePlacementInfo(taskParams.nodeDetailsSet, taskParams.placementInfo);
      // Create a new universe. This makes sure that a universe of this name does not already exist
      // for this customer id.
      Universe universe = Universe.create(taskParams.userIntent.universeName,
        taskParams.universeUUID,
        customer.getCustomerId());
      LOG.info("Created universe {} : {}.", universe.universeUUID, universe.name);

      // Add an entry for the universe into the customer table.
      customer.addUniverseUUID(universe.universeUUID);
      customer.save();

      LOG.info("Added universe {} : {} for customer [{}].",
        universe.universeUUID, universe.name, customer.getCustomerId());

      // Submit the task to create the universe.
      UUID taskUUID = commissioner.submit(TaskType.CreateUniverse, taskParams);
      LOG.info("Submitted create universe for {}:{}, task uuid = {}.",
        universe.universeUUID, universe.name, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(customer,
                          universe.universeUUID,
                          taskUUID,
                          CustomerTask.TargetType.Universe,
                          CustomerTask.TaskType.Create,
                          universe.name);
      LOG.info("Saved task uuid " + taskUUID + " in customer tasks table for universe " +
        universe.universeUUID + ":" + universe.name);

      ObjectNode resultNode = (ObjectNode)universe.toJson();
      resultNode.put("taskUUID", taskUUID.toString());
      return Results.status(OK, resultNode);
    } catch (Throwable t) {
      LOG.error("Error creating universe", t);
      return ApiResponse.error(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  /**
   * API that queues a task to update/edit a universe of a given customer.
   * This does not wait for the completion.
   *
   * @return result of the universe update operation.
   */
  public Result update(UUID customerUUID, UUID universeUUID) {
    UniverseDefinitionTaskParams taskParams;
    try {
      LOG.info("Update {} for {}.", customerUUID, universeUUID);
      // Get the user submitted form data.

      ObjectNode formData = (ObjectNode) request().body().asJson();
      taskParams = bindFormDataToTaskParams(formData);
    } catch (Throwable t) {
      return ApiResponse.error(BAD_REQUEST, t.getMessage());
    }
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    try {
      // Get the universe. This makes sure that a universe of this name does exist
      // for this customer id.
      Universe universe = Universe.get(universeUUID);
      updatePlacementInfo(taskParams.nodeDetailsSet, taskParams.placementInfo);
      LOG.info("Found universe {} : name={} at version={}.",
               universe.universeUUID, universe.name, universe.version);
      UUID taskUUID = commissioner.submit(TaskType.EditUniverse, taskParams);
      LOG.info("Submitted edit universe for {} : {}, task uuid = {}.",
        universe.universeUUID, universe.name, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.Update,
        universe.name);
      LOG.info("Saved task uuid {} in customer tasks table for universe {} : {}.", taskUUID,
        universe.universeUUID, universe.name);
      ObjectNode resultNode = (ObjectNode)universe.toJson();
      resultNode.put("taskUUID", taskUUID.toString());
      return Results.status(OK, resultNode);
    } catch (Throwable t) {
      LOG.error("Error updating universe", t);
      return ApiResponse.error(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  /**
   * List the universes for a given customer.
   *
   * @return
   */
  public Result list(UUID customerUUID) {
    // Verify the customer is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    ArrayNode universes = Json.newArray();
    // TODO: Restrict the list api json payload, possibly to only include UUID, Name etc
    for (Universe universe: customer.getUniverses()) {
      ObjectNode universePayload = (ObjectNode) universe.toJson();
      try {
        UniverseResourceDetails details = UniverseResourceDetails.create(universe.getNodes(),
            universe.getUniverseDetails());
        universePayload.put("pricePerHour", details.pricePerHour);
      } catch (Exception e) {
        LOG.error("Unable to fetch cost for universe {}.", universe.universeUUID);
      }
      universes.add(universePayload);
    }
    return ApiResponse.success(universes);
  }

  public Result index(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    try {
      Universe universe = Universe.get(universeUUID);
      return Results.status(OK, universe.toJson());
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Universe UUID: " + universeUUID);
    }
  }

  public Result destroy(UUID customerUUID, UUID universeUUID) {
    LOG.info("Destroy universe, customer uuid: {}, universeUUID: {} ", customerUUID, universeUUID);

    Boolean isForceDelete = false;
    if (request().getQueryString("isForceDelete") != null) {
      isForceDelete = Boolean.valueOf(request().getQueryString("isForceDelete"));
    }

    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    Universe universe;
    // Make sure the universe exists, this method will throw an exception if it does not.
    try {
      universe = Universe.get(universeUUID);
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found with UUID: " + universeUUID);
    }

    // Create the Commissioner task to destroy the universe.
    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.universeUUID = universeUUID;
    // There is no staleness of a delete request. Perform it even if the universe has changed.
    taskParams.expectedUniverseVersion = -1;
    taskParams.cloud = universe.getUniverseDetails().userIntent.providerType;
    taskParams.customerUUID = customerUUID;
    taskParams.isForceDelete = isForceDelete;
    // Submit the task to destroy the universe.
    UUID taskUUID = commissioner.submit(TaskType.DestroyUniverse, taskParams);
    LOG.info("Submitted destroy universe for " + universeUUID + ", task uuid = " + taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(customer,
      universe.universeUUID,
      taskUUID,
      CustomerTask.TargetType.Universe,
      CustomerTask.TaskType.Delete,
      universe.name);

    LOG.info("Dropped universe " + universeUUID + " for customer [" + customer.name + "]");

    ObjectNode response = Json.newObject();
    response.put("taskUUID", taskUUID.toString());
    return ApiResponse.success(response);
  }

  public Result universeCost(UUID customerUUID, UUID universeUUID) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    Universe universe;
    // Make sure the universe exists, this method will throw an exception if it does not.
    try {
      universe = Universe.get(universeUUID);
    }
    catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found with UUID: " + universeUUID);
    }
    try {
      return ApiResponse.success(Json.toJson(UniverseResourceDetails.create(universe.getNodes(),
          universe.getUniverseDetails())));
    }
    catch (Exception e) {
      return ApiResponse.error(INTERNAL_SERVER_ERROR,
        "Error getting cost for customer " + customerUUID);
    }
  }

  public Result universeListCost(UUID customerUUID) {
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    ArrayNode response = Json.newArray();
    Set<Universe> universeSet = null;
    try {
      universeSet = customer.getUniverses();
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found for customer with ID: " + customerUUID);
    }
    try {
      for (Universe universe : universeSet) {
        response.add(Json.toJson(UniverseResourceDetails.create(universe.getNodes(),
            universe.getUniverseDetails())));
      }
    } catch (Exception e) {
      return ApiResponse.error(INTERNAL_SERVER_ERROR,
        "Error getting cost for customer " + customerUUID);
    }
    return ApiResponse.success(response);
  }

  /**
   * API that queues a task to perform an upgrade and a subsequent rolling restart of a universe.
   *
   * @return result of the universe update operation.
   */
  public Result upgrade(UUID customerUUID, UUID universeUUID) {
    try {
      LOG.info("Upgrade {} for {}.", customerUUID, universeUUID);

      // Verify the customer with this universe is present.
      Customer customer = Customer.get(customerUUID);
      if (customer == null) {
        return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
      }
      ObjectNode formData = (ObjectNode) request().body().asJson();

      RollingRestartParams taskParams = bindRollingRestartFormDataToTaskParams(formData);

      if (taskParams.taskType == null) {
        return ApiResponse.error(BAD_REQUEST, "task type is required");
      }

      CustomerTask.TaskType customerTaskType = null;
      // Validate if any required params are missed based on the taskType
      switch(taskParams.taskType) {
        case Software:
          customerTaskType = CustomerTask.TaskType.UpgradeSoftware;
          if (taskParams.ybSoftwareVersion == null || taskParams.ybSoftwareVersion.isEmpty()) {
            return ApiResponse.error(
              BAD_REQUEST,
              "ybSoftwareVersion param is required for taskType: " + taskParams.taskType);
          }
          break;
        case GFlags:
          customerTaskType = CustomerTask.TaskType.UpgradeGflags;
          if ((taskParams.masterGFlags == null || taskParams.masterGFlags.isEmpty()) &&
            (taskParams.tserverGFlags == null || taskParams.tserverGFlags.isEmpty())) {
            return ApiResponse.error(
              BAD_REQUEST,
              "gflags param is required for taskType: " + taskParams.taskType);
          }
          break;
      }

      LOG.info("Got task type {}", customerTaskType.toString());

      // Get the universe. This makes sure that a universe of this name does exist
      // for this customer id.
      Universe universe = Universe.get(universeUUID);
      taskParams.universeUUID = universe.universeUUID;
      taskParams.expectedUniverseVersion = universe.version;
      LOG.info("Found universe {} : name={} at version={}.",
        universe.universeUUID, universe.name, universe.version);

      UUID taskUUID = commissioner.submit(TaskType.UpgradeUniverse, taskParams);
      LOG.info("Submitted upgrade universe for {} : {}, task uuid = {}.",
        universe.universeUUID, universe.name, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        customerTaskType,
        universe.name);
      LOG.info("Saved task uuid {} in customer tasks table for universe {} : {}.", taskUUID,
        universe.universeUUID, universe.name);
      ObjectNode resultNode = Json.newObject();
      resultNode.put("taskUUID", taskUUID.toString());
      return Results.status(OK, resultNode);
    } catch (Throwable t) {
      LOG.error("Error updating universe", t);
      return ApiResponse.error(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  /**
   * API that checks the status of the the tservers and masters in the universe.
   *
   * @return result of the universe status operation.
   */
  public Result status(UUID customerUUID, UUID universeUUID) {

    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    // Make sure the universe exists, this method will throw an exception if it does not.
    Universe universe;
    try {
      universe = Universe.get(universeUUID);
    }
    catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found with UUID: " + universeUUID);
    }

    // Get alive status
    try {
      JsonNode result = PlacementInfoUtil.getUniverseAliveStatus(universe, metricQueryHelper);
      return result.has("error") ? ApiResponse.error(BAD_REQUEST, result.get("error")) : ApiResponse.success(result);
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Endpoint to retrieve the IP of the master leader for a given universe.
   *
   * @param customerUUID UUID of Customer the target Universe belongs to.
   * @param universeUUID UUID of Universe to retrieve the master leader private IP of.
   * @return The private IP of the master leader.
   */
  public Result getMasterLeaderIP(UUID customerUUID, UUID universeUUID) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    // Make sure the universe exists, this method will throw an exception if it does not.
    Universe universe;
    try {
      universe = Universe.get(universeUUID);
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found with UUID: " + universeUUID);
    }

    // Get and return Leader IP
    try {
      String hostPorts = universe.getMasterAddresses();
      YBClient client = ybService.getClient(hostPorts);
      ObjectNode result = Json.newObject().put("privateIP", client.getLeaderMasterHostAndPort().getHostText());
      ybService.closeClient(client, hostPorts);
      return ApiResponse.success(result);
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, e.getMessage());
    }
  }

  private RollingRestartParams bindRollingRestartFormDataToTaskParams(ObjectNode formData) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RollingRestartParams taskParams = new RollingRestartParams();

    Map<String, String> masterGFlagsMap = serializeGFlagListToMap(formData, "masterGFlags");
    Map<String, String> tserverGFlagsMap = serializeGFlagListToMap(formData, "tserverGFlags");

    // Nodes Option for RollingRestartParams will no longer be supported.
    formData.remove("nodeNames");

    taskParams = mapper.treeToValue(formData, RollingRestartParams.class);
    taskParams.masterGFlags = masterGFlagsMap;
    taskParams.tserverGFlags = tserverGFlagsMap;
    return taskParams;
  }

  private UniverseDefinitionTaskParams bindFormDataToTaskParams(ObjectNode formData) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    ArrayNode nodeSetArray = null;
    int expectedUniverseVersion = -1;
    if (formData.get("nodeDetailsSet") != null) {
      nodeSetArray = (ArrayNode)formData.get("nodeDetailsSet");
      formData.remove("nodeDetailsSet");
    }
    if (formData.get("expectedUniverseVersion") != null) {
      expectedUniverseVersion = formData.get("expectedUniverseVersion").asInt();
    }
    ObjectNode userIntent = (ObjectNode) formData.get("userIntent");
    if (userIntent == null ) {
      throw new Exception("userIntent: This field is required");
    }
    Map<String, String> masterGFlagsMap = serializeGFlagListToMap(userIntent, "masterGFlags");
    Map<String, String> tserverGFlagsMap = serializeGFlagListToMap(userIntent, "tserverGFlags");
    formData.set("userIntent", userIntent);
    taskParams = mapper.treeToValue(formData, UniverseDefinitionTaskParams.class);
    if (nodeSetArray != null) {
      Set<NodeDetails> nodeDetailSet = new HashSet<NodeDetails>();
      for (JsonNode nodeItem : nodeSetArray) {
        ObjectNode tempObjectNode = ((ObjectNode) nodeItem);
        NodeDetails node = mapper.treeToValue(tempObjectNode, NodeDetails.class);
        nodeDetailSet.add(node);
      }
      taskParams.nodeDetailsSet = nodeDetailSet;
    }
    taskParams.userIntent.masterGFlags = masterGFlagsMap;
    taskParams.userIntent.tserverGFlags = tserverGFlagsMap;
    taskParams.expectedUniverseVersion = expectedUniverseVersion;
    return taskParams;
  }

  /**
   * Method serializes the GFlag ObjectNode into a Map and then deletes it from it's parent node.
   * @param formNode Parent FormObject for the GFlag Node.
   * @param listType Type of GFlag object
   * @return Serialized JSON array into Map
   */
  private Map<String, String>  serializeGFlagListToMap(ObjectNode formNode, String listType) {
    Map<String, String> gflagMap = new HashMap<>();
    JsonNode formNodeList = formNode.get(listType);
    if (formNodeList != null && formNodeList.isArray()) {
      ArrayNode flagNodeArray = (ArrayNode) formNodeList;
      for (int counter = 0; counter < flagNodeArray.size(); counter++) {
        if (flagNodeArray.get(counter).get("name") != null) {
          String key = flagNodeArray.get(counter).get("name").asText();
          String value = flagNodeArray.get(counter).get("value").asText();
          gflagMap.put(key, value);
        }
      }
    }
    formNode.remove(listType);
    return gflagMap;
  }
}

