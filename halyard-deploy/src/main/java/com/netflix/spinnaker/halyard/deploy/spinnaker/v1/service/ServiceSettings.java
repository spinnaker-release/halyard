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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.netflix.spinnaker.halyard.core.error.v1.HalException;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem;
import lombok.Data;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.utils.URIBuilder;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * These are the attributes of a service that can conceivably change between deployments to the exact same deployment
 * environment. Username and Password are easy examples, since these should be updated with each deployment. Port and Address
 * can be changed, so we'll keep these here too. On the other hand, properties like Name, ArtifactType, etc... are all
 * fixed, and can't be changed between deployments. Ideally, if someone supplied ServiceSettings to Halyard, Halyard would
 * have everything it needs to run a deployment.
 */
@Data
abstract public class ServiceSettings {
  int port;
  // A port open only to the internal network
  Integer internalPort;
  String address;
  String host;
  String scheme;
  String healthEndpoint;
  @JsonIgnore String username;
  @JsonIgnore String password;
  Map<String, String> env = new HashMap<>();
  String artifactId;
  String overrideBaseUrl;
  String location;
  boolean enabled;
  boolean basicAuthEnabled;
  boolean monitored;
  boolean sidecar;
  boolean safeToUpdate;
  int targetSize = 1;

  public ServiceSettings() {}

  private URIBuilder buildBaseUri() {
    if (StringUtils.isEmpty(overrideBaseUrl)) {
      return new URIBuilder()
          .setScheme(getScheme())
          .setPort(getPort())
          .setHost(getAddress());
    } else {
      try {
        return new URIBuilder(overrideBaseUrl);
      } catch (URISyntaxException e) {
        throw new HalException(Problem.Severity.FATAL, "Illegal override baseURL: " + overrideBaseUrl, e);
      }
    }
  }

  @JsonIgnore
  public String getAuthBaseUrl() {
    return buildBaseUri()
        .setUserInfo(getUsername(), getPassword())
        .toString();
  }

  public String getBaseUrl() {
    return buildBaseUri().toString();
  }
}
