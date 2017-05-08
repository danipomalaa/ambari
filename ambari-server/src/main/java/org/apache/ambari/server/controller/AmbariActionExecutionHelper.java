/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.controller;

import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.AGENT_STACK_RETRY_COUNT;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.AGENT_STACK_RETRY_ON_UNAVAILABILITY;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.COMMAND_TIMEOUT;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.COMPONENT_CATEGORY;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.REPO_INFO;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.SCRIPT;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.SCRIPT_TYPE;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.STACK_NAME;
import static org.apache.ambari.server.agent.ExecutionCommand.KeyNames.STACK_VERSION;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ObjectNotFoundException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.StackAccessException;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.TargetHostType;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.agent.ExecutionCommand.KeyNames;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.internal.RequestResourceFilter;
import org.apache.ambari.server.customactions.ActionDefinition;
import org.apache.ambari.server.orm.entities.OperatingSystemEntity;
import org.apache.ambari.server.orm.entities.RepositoryEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceInfo;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostOpInProgressEvent;
import org.apache.ambari.server.utils.SecretReference;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Helper class containing logic to process custom action execution requests
 */
@Singleton
public class AmbariActionExecutionHelper {
  private final static Logger LOG =
      LoggerFactory.getLogger(AmbariActionExecutionHelper.class);
  private static final String TYPE_PYTHON = "PYTHON";
  private static final String ACTION_UPDATE_REPO = "update_repo";
  private static final String SUCCESS_FACTOR_PARAMETER = "success_factor";

  private static final float UPDATE_REPO_SUCCESS_FACTOR_DEFAULT = 0f;

  @Inject
  private Clusters clusters;
  @Inject
  private AmbariManagementController managementController;
  @Inject
  private AmbariMetaInfo ambariMetaInfo;
  @Inject
  private MaintenanceStateHelper maintenanceStateHelper;
  @Inject
  private Configuration configs;

  /**
   * Validates the request to execute an action.
   * @param actionRequest
   * @throws AmbariException
   */
  public void validateAction(ExecuteActionRequest actionRequest) throws AmbariException {
    if (actionRequest.getActionName() == null || actionRequest.getActionName().isEmpty()) {
      throw new AmbariException("Action name must be specified");
    }

    ActionDefinition actionDef = ambariMetaInfo.getActionDefinition(actionRequest.getActionName());
    if (actionDef == null) {
      throw new AmbariException("Action " + actionRequest.getActionName() + " does not exist");
    }

    if (actionDef.getInputs() != null) {
      String[] inputs = actionDef.getInputs().split(",");
      for (String input : inputs) {
        String inputName = input.trim();
        if (!inputName.isEmpty()) {
          boolean mandatory = true;
          if (inputName.startsWith("[") && inputName.endsWith("]")) {
            mandatory = false;
          }
          if (mandatory && !actionRequest.getParameters().containsKey(inputName)) {
            throw new AmbariException("Action " + actionRequest.getActionName() + " requires input '" +
              input.trim() + "' that is not provided.");
          }
        }
      }
    }

    List<RequestResourceFilter> resourceFilters = actionRequest.getResourceFilters();
    RequestResourceFilter resourceFilter = null;
    if (resourceFilters != null && !resourceFilters.isEmpty()) {
      if (resourceFilters.size() > 1) {
        throw new AmbariException("Custom action definition only allows one " +
          "resource filter to be specified.");
      } else {
        resourceFilter = resourceFilters.get(0);
      }
    }

    String targetService = "";
    String targetComponent = "";

    if (null != actionRequest.getClusterName()) {
      Cluster cluster = clusters.getCluster(actionRequest.getClusterName());

      if (cluster == null) {
        throw new AmbariException("Unable to find cluster. clusterName = " +
          actionRequest.getClusterName());
      }

      StackId stackId = cluster.getCurrentStackVersion();

      String expectedService = actionDef.getTargetService() == null ? "" : actionDef.getTargetService();

      String actualService = resourceFilter == null || resourceFilter.getServiceName() == null ? "" : resourceFilter.getServiceName();
      if (!expectedService.isEmpty() && !actualService.isEmpty() && !expectedService.equals(actualService)) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " targets service " + actualService +
          " that does not match with expected " + expectedService);
      }

      targetService = expectedService;
      if (targetService == null || targetService.isEmpty()) {
        targetService = actualService;
      }

      if (targetService != null && !targetService.isEmpty()) {
        ServiceInfo serviceInfo;
        try {
          serviceInfo = ambariMetaInfo.getService(stackId.getStackName(), stackId.getStackVersion(),
            targetService);
        } catch (StackAccessException se) {
          serviceInfo = null;
        }

        if (serviceInfo == null) {
          throw new AmbariException("Action " + actionRequest.getActionName() +
            " targets service " + targetService + " that does not exist.");
        }
      }

      String expectedComponent = actionDef.getTargetComponent() == null ? "" : actionDef.getTargetComponent();
      String actualComponent = resourceFilter == null || resourceFilter.getComponentName() == null ? "" : resourceFilter.getComponentName();
      if (!expectedComponent.isEmpty() && !actualComponent.isEmpty() && !expectedComponent.equals(actualComponent)) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " targets component " + actualComponent +
          " that does not match with expected " + expectedComponent);
      }

      targetComponent = expectedComponent;
      if (targetComponent == null || targetComponent.isEmpty()) {
        targetComponent = actualComponent;
      }

      if (!targetComponent.isEmpty() && targetService.isEmpty()) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " targets component " + targetComponent +
          " without specifying the target service.");
      }

      if (targetComponent != null && !targetComponent.isEmpty()) {
        ComponentInfo compInfo;
        try {
          compInfo = ambariMetaInfo.getComponent(stackId.getStackName(), stackId.getStackVersion(),
            targetService, targetComponent);
        } catch (StackAccessException se) {
          compInfo = null;
        }

        if (compInfo == null) {
          throw new AmbariException("Action " + actionRequest.getActionName() + " targets component " + targetComponent +
            " that does not exist.");
        }
      }
    }

    TargetHostType targetHostType = actionDef.getTargetType();

    if (TargetHostType.SPECIFIC.equals(targetHostType)
      || (targetService.isEmpty() && targetComponent.isEmpty())) {
      if ((resourceFilter == null || resourceFilter.getHostNames().size() == 0) && !isTargetHostTypeAllowsEmptyHosts(targetHostType)) {
        throw new AmbariException("Action " + actionRequest.getActionName() + " requires explicit target host(s)" +
          " that is not provided.");
      }
    }
  }

  private boolean isTargetHostTypeAllowsEmptyHosts(TargetHostType targetHostType) {
    return targetHostType.equals(TargetHostType.ALL) || targetHostType.equals(TargetHostType.ANY)
            || targetHostType.equals(TargetHostType.MAJORITY);
  }

  /**
   * Add tasks to the stage based on the requested action execution
   * @param actionContext  the context associated with the action
   * @param stage          stage into which tasks must be inserted
   * @param requestParams  all request parameters (may be null)
   * @throws AmbariException if the task can not be added
   */
  public void addExecutionCommandsToStage(final ActionExecutionContext actionContext, Stage stage,
                                          Map<String, String> requestParams) throws AmbariException {
    addExecutionCommandsToStage(actionContext, stage, requestParams, true);
  }

  /**
   * Add tasks to the stage based on the requested action execution
   * @param actionContext
   * @param stage
   * @param requestParams
   * @param checkHostIsMemberOfCluster if true AmbariException will be thrown in case host is not member of cluster.
   * @throws AmbariException
   */
  public void addExecutionCommandsToStage(final ActionExecutionContext actionContext, Stage stage,
                                          Map<String, String> requestParams, boolean checkHostIsMemberOfCluster)
      throws AmbariException {

    String actionName = actionContext.getActionName();
    String clusterName = actionContext.getClusterName();
    final Cluster cluster;
    if (null != clusterName) {
      cluster = clusters.getCluster(clusterName);
    } else {
      cluster = null;
    }

    ComponentInfo componentInfo = null;
    List<RequestResourceFilter> resourceFilters = actionContext.getResourceFilters();
    final RequestResourceFilter resourceFilter;
    if (resourceFilters != null && !resourceFilters.isEmpty()) {
      resourceFilter = resourceFilters.get(0);
    } else {
      resourceFilter = new RequestResourceFilter();
    }

    // List of host to select from
    Set<String> candidateHosts = new HashSet<>();

    final String serviceName = actionContext.getExpectedServiceName();
    final String componentName = actionContext.getExpectedComponentName();

    LOG.debug("Called addExecutionCommandsToStage() for serviceName: {}, componentName: {}.", serviceName, componentName);
    if (resourceFilter.getHostNames().isEmpty()) {
      LOG.debug("Resource filter has no hostnames.");
    } else {
      LOG.debug("Resource filter has hosts: {}", StringUtils.join(resourceFilter.getHostNames(), ", "));
    }

    if (null != cluster) {
      StackId stackId = cluster.getCurrentStackVersion();
      if (serviceName != null && !serviceName.isEmpty()) {
        if (componentName != null && !componentName.isEmpty()) {
          Map<String, ServiceComponentHost> componentHosts =
            cluster.getService(serviceName)
              .getServiceComponent(componentName).getServiceComponentHosts();
          candidateHosts.addAll(componentHosts.keySet());
          try {
            componentInfo = ambariMetaInfo.getComponent(stackId.getStackName(),
                stackId.getStackVersion(), serviceName, componentName);
          } catch (ObjectNotFoundException e) {
            // do nothing, componentId is checked for null later
            LOG.error("Did not find service {} and component {} in stack {}.", serviceName, componentName, stackId.getStackName());
          }
        } else {
          for (String component : cluster.getService(serviceName).getServiceComponents().keySet()) {
            Map<String, ServiceComponentHost> componentHosts =
              cluster.getService(serviceName)
                .getServiceComponent(component).getServiceComponentHosts();
            candidateHosts.addAll(componentHosts.keySet());
          }
        }
      } else {
        // All hosts are valid target host
        candidateHosts.addAll(clusters.getHostsForCluster(cluster.getClusterName()).keySet());
      }
      LOG.debug("Request for service {} and component {} is set to run on candidate hosts: {}.", serviceName, componentName, StringUtils.join(candidateHosts, ", "));

      // Filter hosts that are in MS
      Set<String> ignoredHosts = maintenanceStateHelper.filterHostsInMaintenanceState(
              candidateHosts, new MaintenanceStateHelper.HostPredicate() {
                @Override
                public boolean shouldHostBeRemoved(final String hostname)
                        throws AmbariException {
                  return ! maintenanceStateHelper.isOperationAllowed(
                          cluster, actionContext.getOperationLevel(),
                          resourceFilter, serviceName, componentName, hostname);
                }
              }
      );

      if (! ignoredHosts.isEmpty()) {
        LOG.debug("Hosts to ignore: {}.", StringUtils.join(ignoredHosts, ", "));
        LOG.debug("Ignoring action for hosts due to maintenance state." +
            "Ignored hosts =" + ignoredHosts + ", component="
            + componentName + ", service=" + serviceName
            + ", cluster=" + cluster.getClusterName() + ", " +
            "actionName=" + actionContext.getActionName());
      }
    }

    // If request did not specify hosts and there exists no host
    if (resourceFilter.getHostNames().isEmpty() && candidateHosts.isEmpty()) {
      throw new AmbariException("Suitable hosts not found, component="
              + componentName + ", service=" + serviceName
              + ((null == cluster) ? "" : ", cluster=" + cluster.getClusterName() + ", ")
              + "actionName=" + actionContext.getActionName());
    }

    if (checkHostIsMemberOfCluster) {
      // Compare specified hosts to available hosts
      if (!resourceFilter.getHostNames().isEmpty() && !candidateHosts.isEmpty()) {
        for (String hostname : resourceFilter.getHostNames()) {
          if (!candidateHosts.contains(hostname)) {
            throw new AmbariException("Request specifies host " + hostname +
              " but it is not a valid host based on the " +
              "target service=" + serviceName + " and component=" + componentName);
          }
        }
      }
    }

    List<String> targetHosts = resourceFilter.getHostNames();

    //Find target hosts to execute
    if (targetHosts.isEmpty()) {
      TargetHostType hostType = actionContext.getTargetType();
      switch (hostType) {
        case ALL:
          targetHosts.addAll(candidateHosts);
          break;
        case ANY:
          targetHosts.add(managementController.getHealthyHost(candidateHosts));
          break;
        case MAJORITY:
          for (int i = 0; i < (candidateHosts.size() / 2) + 1; i++) {
            String hostname = managementController.getHealthyHost(candidateHosts);
            targetHosts.add(hostname);
            candidateHosts.remove(hostname);
          }
          break;
        default:
          throw new AmbariException("Unsupported target type = " + hostType);
      }
    }

    setAdditionalParametersForStageAccordingToAction(stage, actionContext);

    // create tasks for each host
    for (String hostName : targetHosts) {
      // ensure that any tags that need to be refreshed are extracted from the
      // context and put onto the execution command
      Map<String, String> actionParameters = actionContext.getParameters();

      stage.addHostRoleExecutionCommand(hostName, Role.valueOf(actionContext.getActionName()),
          RoleCommand.ACTIONEXECUTE,
          new ServiceComponentHostOpInProgressEvent(actionContext.getActionName(), hostName,
              System.currentTimeMillis()),
          clusterName, serviceName, actionContext.isRetryAllowed(),
          actionContext.isFailureAutoSkipped());

      Map<String, String> commandParams = new TreeMap<>();

      int taskTimeout = Integer.parseInt(configs.getDefaultAgentTaskTimeout(false));

      // use the biggest of all these:
      // if the action context timeout is bigger than the default, use the context
      // if the action context timeout is smaller than the default, use the default
      // if the action context timeout is undefined, use the default
      if (null != actionContext.getTimeout() && actionContext.getTimeout() > taskTimeout) {
        commandParams.put(COMMAND_TIMEOUT, actionContext.getTimeout().toString());
      } else {
        commandParams.put(COMMAND_TIMEOUT, Integer.toString(taskTimeout));
      }

      if (requestParams != null && requestParams.containsKey(KeyNames.LOG_OUTPUT)) {
        LOG.info("Should command log output?: " + requestParams.get(KeyNames.LOG_OUTPUT));
        commandParams.put(KeyNames.LOG_OUTPUT, requestParams.get(KeyNames.LOG_OUTPUT));
      }

      commandParams.put(SCRIPT, actionName + ".py");
      commandParams.put(SCRIPT_TYPE, TYPE_PYTHON);

      ExecutionCommand execCmd = stage.getExecutionCommandWrapper(hostName,
        actionContext.getActionName()).getExecutionCommand();

      // !!! ensure that these are empty so that commands have the correct tags
      // applied when the execution is about to be scheduled to run
      execCmd.setConfigurations(new TreeMap<String, Map<String, String>>());
      execCmd.setConfigurationAttributes(new TreeMap<String, Map<String, Map<String, String>>>());

      // if the command should fetch brand new configuration tags before
      // execution, then we don't need to fetch them now
      if (null != actionParameters && !actionParameters.isEmpty()) {
        if (actionParameters.containsKey(KeyNames.REFRESH_CONFIG_TAGS_BEFORE_EXECUTION)) {
          execCmd.setForceRefreshConfigTagsBeforeExecution(true);
        }
      }

      // when building complex orchestration ahead of time (such as when
      // performing ugprades), fetching configuration tags can take a very long
      // time - if it's not needed, then don't do it
      Map<String, Map<String, String>> configTags = new TreeMap<>();
      if (!execCmd.getForceRefreshConfigTagsBeforeExecution()) {
        configTags = managementController.findConfigurationTagsWithOverrides(cluster, hostName);
      }

      execCmd.setConfigurationTags(configTags);

      execCmd.setServiceName(serviceName == null || serviceName.isEmpty() ?
        resourceFilter.getServiceName() : serviceName);

      execCmd.setComponentName(componentName == null || componentName.isEmpty() ?
        resourceFilter.getComponentName() : componentName);

      Map<String, String> hostLevelParams = execCmd.getHostLevelParams();
      hostLevelParams.put(AGENT_STACK_RETRY_ON_UNAVAILABILITY, configs.isAgentStackRetryOnInstallEnabled());
      hostLevelParams.put(AGENT_STACK_RETRY_COUNT, configs.getAgentStackRetryOnInstallCount());
      for (Map.Entry<String, String> dbConnectorName : configs.getDatabaseConnectorNames().entrySet()) {
        hostLevelParams.put(dbConnectorName.getKey(), dbConnectorName.getValue());
      }
      for (Map.Entry<String, String> previousDBConnectorName : configs.getPreviousDatabaseConnectorNames().entrySet()) {
        hostLevelParams.put(previousDBConnectorName.getKey(), previousDBConnectorName.getValue());
      }

      if (StringUtils.isNotBlank(serviceName)) {
        Service service = cluster.getService(serviceName);
        addRepoInfoToHostLevelParams(service.getDesiredRepositoryVersion(), hostLevelParams, hostName);
      }


      Map<String, String> roleParams = execCmd.getRoleParams();
      if (roleParams == null) {
        roleParams = new TreeMap<>();
      }

      roleParams.putAll(actionParameters);

      SecretReference.replaceReferencesWithPasswords(roleParams, cluster);

      if (componentInfo != null) {
        roleParams.put(COMPONENT_CATEGORY, componentInfo.getCategory());
      }

      // if there is a stack upgrade which is currently suspended then pass that
      // information down with the command as some components may need to know
      if (null != cluster && cluster.isUpgradeSuspended()) {
        cluster.addSuspendedUpgradeParameters(commandParams, roleParams);
      }

      execCmd.setCommandParams(commandParams);
      execCmd.setRoleParams(roleParams);

      if (null != cluster) {
        // Generate localComponents
        for (ServiceComponentHost sch : cluster.getServiceComponentHosts(hostName)) {
          execCmd.getLocalComponents().add(sch.getServiceComponentName());
        }
      }

      actionContext.visitAll(execCmd);
    }
  }

  /*
  * This method adds additional properties
  * to action params. For example: success factor.
  *
  * */

  private void setAdditionalParametersForStageAccordingToAction(Stage stage, ActionExecutionContext actionExecutionContext) throws AmbariException {
    if (actionExecutionContext.getActionName().equals(ACTION_UPDATE_REPO)) {
      Map<String, String> params = actionExecutionContext.getParameters();
      float successFactor = UPDATE_REPO_SUCCESS_FACTOR_DEFAULT;
      if (params != null && params.containsKey(SUCCESS_FACTOR_PARAMETER)) {
        try{
          successFactor = Float.valueOf(params.get(SUCCESS_FACTOR_PARAMETER));
        } catch (Exception ex) {
          throw new AmbariException("Failed to cast success_factor value to float!", ex.getCause());
        }
      }
      stage.getSuccessFactors().put(Role.UPDATE_REPO, successFactor);
    }
  }

  /*
  * This method builds and adds repo info
  * to hostLevelParams of action
  *
  * */

  private void addRepoInfoToHostLevelParams(RepositoryVersionEntity repositoryVersion,
      Map<String, String> hostLevelParams, String hostName) throws AmbariException {
    if (null == repositoryVersion) {
      return;
    }

    JsonObject rootJsonObject = new JsonObject();
    JsonArray repositories = new JsonArray();

    String hostOsFamily = clusters.getHost(hostName).getOsFamily();
    for (OperatingSystemEntity operatingSystemEntity : repositoryVersion.getOperatingSystems()) {
      // ostype in OperatingSystemEntity it's os family. That should be fixed
      // in OperatingSystemEntity.
      if (operatingSystemEntity.getOsType().equals(hostOsFamily)) {
        for (RepositoryEntity repositoryEntity : operatingSystemEntity.getRepositories()) {
          JsonObject repositoryInfo = new JsonObject();
          repositoryInfo.addProperty("base_url", repositoryEntity.getBaseUrl());
          repositoryInfo.addProperty("repo_name", repositoryEntity.getName());
          repositoryInfo.addProperty("repo_id", repositoryEntity.getRepositoryId());

          repositories.add(repositoryInfo);
        }
        rootJsonObject.add("repositories", repositories);
      }
    }

    hostLevelParams.put(REPO_INFO, rootJsonObject.toString());

    hostLevelParams.put(STACK_NAME, repositoryVersion.getStackName());
    hostLevelParams.put(STACK_VERSION, repositoryVersion.getStackVersion());
  }
}
