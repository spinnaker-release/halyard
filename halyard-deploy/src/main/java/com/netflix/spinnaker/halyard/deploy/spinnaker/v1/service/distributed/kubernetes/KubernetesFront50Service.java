/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes;

import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.deploy.services.v1.ArtifactService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.Front50Service;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.ServiceInterfaceFactory;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@EqualsAndHashCode(callSuper = true)
@Component
@Data
public class KubernetesFront50Service extends Front50Service implements KubernetesDistributedService<Front50Service.Front50> {
  @Autowired
  private String dockerRegistry;

  @Autowired
  KubernetesMonitoringDaemonService monitoringDaemonService;

  @Autowired
  ArtifactService artifactService;

  @Autowired
  ServiceInterfaceFactory serviceInterfaceFactory;

  @Override
  public Settings buildServiceSettings(DeploymentConfiguration deploymentConfiguration) {
    Settings settings = new Settings();
    String location = "spinnaker";
    settings.setAddress(buildAddress(location))
        .setArtifactId(getArtifactId(deploymentConfiguration.getName()))
        .setLocation(location)
        .setEnabled(true);
    return settings;
  }

  public String getArtifactId(String deploymentName) {
    return KubernetesDistributedService.super.getArtifactId(deploymentName);
  }

  final DeployPriority deployPriority = new DeployPriority(4);
  final boolean requiredToBootstrap = false;
}
