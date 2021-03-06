/*
 * Copyright 2018 Lookout, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.ecs.deploy.validators;

import com.amazonaws.services.ecs.model.PlacementStrategy;
import com.amazonaws.services.ecs.model.PlacementStrategyType;
import com.google.common.collect.Sets;
import com.netflix.spinnaker.clouddriver.ecs.EcsOperation;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@EcsOperation(AtomicOperations.CREATE_SERVER_GROUP)
@Component("ecsCreateServerGroupDescriptionValidator")
public class EcsCreateServerGroupDescriptionValidator extends CommonValidator {

  private static final Set<String> BINPACK_VALUES = Sets.newHashSet("cpu", "memory");
  private static final Set<String> SPREAD_VALUES =
      Sets.newHashSet(
          "instanceId",
          "attribute:ecs.availability-zone",
          "attribute:ecs.instance-type",
          "attribute:ecs.os-type",
          "attribute:ecs.ami-id");

  private static final Set<String> RESERVED_ENVIRONMENT_VARIABLES =
      Sets.newHashSet("SERVER_GROUP", "CLOUD_STACK", "CLOUD_DETAIL");

  public EcsCreateServerGroupDescriptionValidator() {
    super("createServerGroupDescription");
  }

  @Override
  public void validate(List priorDescriptions, Object description, Errors errors) {
    CreateServerGroupDescription createServerGroupDescription =
        (CreateServerGroupDescription) description;

    validateCredentials(createServerGroupDescription, errors, "credentials");
    validateCapacity(errors, createServerGroupDescription.getCapacity());

    if (createServerGroupDescription.getAvailabilityZones() != null) {
      if (createServerGroupDescription.getAvailabilityZones().size() != 1) {
        rejectValue(errors, "availabilityZones", "must.have.only.one");
      }
    } else {
      rejectValue(errors, "availabilityZones", "not.nullable");
    }

    if (createServerGroupDescription.getPlacementStrategySequence() != null) {
      for (PlacementStrategy placementStrategy :
          createServerGroupDescription.getPlacementStrategySequence()) {
        PlacementStrategyType type;
        try {
          type = PlacementStrategyType.fromValue(placementStrategy.getType());
        } catch (IllegalArgumentException e) {
          rejectValue(errors, "placementStrategySequence.type", "invalid");
          continue;
        }

        switch (type) {
          case Random:
            break;
          case Spread:
            if (!SPREAD_VALUES.contains(placementStrategy.getField())) {
              rejectValue(errors, "placementStrategySequence.spread", "invalid");
            }
            break;
          case Binpack:
            if (!BINPACK_VALUES.contains(placementStrategy.getField())) {
              rejectValue(errors, "placementStrategySequence.binpack", "invalid");
            }
            break;
        }
      }
    } else {
      rejectValue(errors, "placementStrategySequence", "not.nullable");
    }

    if (createServerGroupDescription.getApplication() == null) {
      rejectValue(errors, "application", "not.nullable");
    }

    if (createServerGroupDescription.getEcsClusterName() == null) {
      rejectValue(errors, "ecsClusterName", "not.nullable");
    }

    if (createServerGroupDescription.getServiceDiscoveryAssociations() != null) {
      for (CreateServerGroupDescription.ServiceDiscoveryAssociation association :
          createServerGroupDescription.getServiceDiscoveryAssociations()) {
        if (association.getRegistry() == null) {
          rejectValue(errors, "serviceDiscoveryAssociations", "item.invalid");
        }
      }
    }

    boolean hasTargetGroup = StringUtils.isNotBlank(createServerGroupDescription.getTargetGroup());

    if (!createServerGroupDescription.isUseTaskDefinitionArtifact()) {
      if (createServerGroupDescription.getDockerImageAddress() == null) {
        rejectValue(errors, "dockerImageAddress", "not.nullable");
      }

      if (createServerGroupDescription.getComputeUnits() != null) {
        if (createServerGroupDescription.getComputeUnits() < 0) {
          rejectValue(errors, "computeUnits", "invalid");
        }
      } else {
        rejectValue(errors, "computeUnits", "not.nullable");
      }

      if (createServerGroupDescription.getReservedMemory() != null) {
        if (createServerGroupDescription.getReservedMemory() < 0) {
          rejectValue(errors, "reservedMemory", "invalid");
        }
      } else {
        rejectValue(errors, "reservedMemory", "not.nullable");
      }
    } else {
      // Verify load balanced services w/ an artifact specify which container to load balance on
      boolean hasLoadBalancedContainer =
          StringUtils.isNotBlank(createServerGroupDescription.getLoadBalancedContainer());

      if (hasTargetGroup && !hasLoadBalancedContainer) {
        rejectValue(errors, "loadBalancedContainer", "not.nullable");
      } else if (!hasTargetGroup && hasLoadBalancedContainer) {
        rejectValue(errors, "targetGroup", "not.nullable");
      }
    }

    if (createServerGroupDescription.getContainerPort() != null) {
      if (createServerGroupDescription.getContainerPort() < 0
          || createServerGroupDescription.getContainerPort() > 65535) {
        rejectValue(errors, "containerPort", "invalid");
      }
    } else if (hasTargetGroup) {
      // if a target group is specified, a container port must be specified
      rejectValue(errors, "containerPort", "not.nullable");
    }

    validateTargetGroupMappings(createServerGroupDescription, errors);

    // Verify that the environment variables set by the user do not contain reserved values
    if (createServerGroupDescription.getEnvironmentVariables() != null) {
      if (!Collections.disjoint(
          createServerGroupDescription.getEnvironmentVariables().keySet(),
          RESERVED_ENVIRONMENT_VARIABLES)) {
        rejectValue(errors, "environmentVariables", "invalid");
      }
    }
  }

  private void validateTargetGroupMappings(
      CreateServerGroupDescription createServerGroupDescription, Errors errors) {
    if (createServerGroupDescription.getTargetGroupMappings() != null
        && !createServerGroupDescription.getTargetGroupMappings().isEmpty()) {

      if (StringUtils.isNotEmpty(createServerGroupDescription.getTargetGroup())) {
        // Only one of TargetGroup or TargetGroupMappings should be defined.
        errors.rejectValue(
            "targetGroup",
            errorKey + "." + "targetGroup" + "." + "invalid",
            "TargetGroup cannot be specified when TargetGroupMapping.TargetGroup is specified. Please use TargetGroupMapping");
      }

      for (CreateServerGroupDescription.TargetGroupProperties targetGroupProperties :
          createServerGroupDescription.getTargetGroupMappings()) {
        // Verify each target group mapping contains a target group name, container name (or docker
        // image address if it's a single container using inputs), and container port.
        boolean hasTargetGroup = StringUtils.isNotBlank(targetGroupProperties.getTargetGroup());
        boolean hasContainerName = StringUtils.isNotBlank(targetGroupProperties.getContainerName());

        if (createServerGroupDescription.isUseTaskDefinitionArtifact()) {
          if (hasTargetGroup && !hasContainerName) {
            rejectValue(errors, "targetGroupMappings.containerName", "not.nullable");
          } else if (!hasTargetGroup && hasContainerName) {
            rejectValue(errors, "targetGroupMappings.targetGroup", "not.nullable");
          }
        }

        if (targetGroupProperties.getContainerPort() != null) {
          if (targetGroupProperties.getContainerPort() < 0
              || targetGroupProperties.getContainerPort() > 65535) {

            rejectValue(errors, "targetGroupMappings.containerPort", "invalid");
          }
        } else if (hasTargetGroup) {
          // if a target group is specified, a container port must be specified
          rejectValue(errors, "targetGroupMappings.containerPort", "not.nullable");
        }
      }
    }
  }
}
