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

package com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service;

import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerArtifact;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile.ConsulClientProfileFactory;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile.ConsulServiceProfileFactoryBuilder;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile.Profile;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile.ProfileFactory;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
@Component
abstract public class ConsulClientService extends SpinnakerService<ConsulClientService.Consul> {
  protected final String CLIENT_OUTPUT_PATH = "/var/consul.d";

  @Autowired
  ConsulServiceProfileFactoryBuilder consulServiceProfileFactoryBuilder;

  @Autowired
  ConsulClientProfileFactory consulClientProfileFactory;

  @Override
  public SpinnakerArtifact getArtifact() {
    return SpinnakerArtifact.CONSUL;
  }

  @Override
  public Type getType() {
    return Type.CONSUL_CLIENT;
  }

  @Override
  public Class<Consul> getEndpointClass() {
    return Consul.class;
  }

  public static String consulClientService(String serviceName) {
    return "consul/" + serviceName + ".json";
  }

  @Override
  public List<Profile> getProfiles(DeploymentConfiguration deploymentConfiguration, SpinnakerRuntimeSettings endpoints) {
    List<Profile> result = new ArrayList<>();
    for (Map.Entry<Type, ServiceSettings> entry : endpoints.getAllServiceSettings().entrySet()) {
      ServiceSettings settings = entry.getValue();
      Type type = entry.getKey();
      if (!settings.isSidecar() && settings.isEnabled()) {
        String profileName = consulClientService(type.getCanonicalName());
        String profilePath = Paths.get(CLIENT_OUTPUT_PATH, profileName).toString();
        ProfileFactory factory = consulServiceProfileFactoryBuilder.build(type, settings);
        result.add(factory.getProfile(profileName, profilePath, deploymentConfiguration, endpoints));
      }
    }

    String profileName = "consul/client.json";
    String profilePath = Paths.get(CLIENT_OUTPUT_PATH, profileName).toString();

    result.add(consulClientProfileFactory.getProfile(profileName, profilePath, deploymentConfiguration, endpoints));
    return result;
  }

  public interface Consul { }

  @EqualsAndHashCode(callSuper = true)
  @Data
  public static class Settings extends ServiceSettings {
    int port = 8500;
    // Address is how the service is looked up.
    String address = "localhost";
    // Host is what's bound to by the service.
    String host = "0.0.0.0";
    String scheme = "http";
    String healthEndpoint = null;
    boolean enabled = true;
    boolean safeToUpdate = true;
    boolean monitored = false;
    boolean sidecar = true;

    public Settings() { }
  }
}
