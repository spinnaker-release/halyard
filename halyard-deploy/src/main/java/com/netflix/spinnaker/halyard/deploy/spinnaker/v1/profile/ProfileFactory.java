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

package com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.config.model.v1.node.Node;
import com.netflix.spinnaker.halyard.core.error.v1.HalException;
import com.netflix.spinnaker.halyard.core.problem.v1.ProblemBuilder;
import com.netflix.spinnaker.halyard.deploy.services.v1.ArtifactService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerArtifact;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.netflix.spinnaker.halyard.core.problem.v1.Problem.Severity.FATAL;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

abstract public class ProfileFactory {
  @Autowired
  private ArtifactService artifactService;

  @Autowired
  private String spinnakerStagingPath;

  @Autowired
  private Yaml yamlParser;

  @Autowired
  private ObjectMapper strictObjectMapper;

  protected boolean showEditWarning() {
    return true;
  }

  protected ArtifactService getArtifactService() {
    return artifactService;
  }

  public Profile getProfile(String name, String outputFile, DeploymentConfiguration deploymentConfiguration, SpinnakerRuntimeSettings endpoints) {
    String deploymentName = deploymentConfiguration.getName();
    String version = getArtifactService().getArtifactVersion(deploymentName, getArtifact());
    Profile result = getBaseProfile(name, version, outputFile);
    setProfile(result, deploymentConfiguration, endpoints);
    if (showEditWarning()) {
      result.preppendContents(getEditWarning());
    }
    return result;
  }

  abstract protected void setProfile(Profile profile, DeploymentConfiguration deploymentConfiguration, SpinnakerRuntimeSettings endpoints);
  abstract protected Profile getBaseProfile(String name, String version, String outputFile);

  abstract public SpinnakerArtifact getArtifact();

  abstract protected String commentPrefix();
  private String getEditWarning() {
    return commentPrefix() + "WARNING\n" +
        commentPrefix() + "This file was autogenerated, and _will_ be overwritten by Halyard.\n" +
        commentPrefix() + "Any edits you make here _will_ be lost.\n";
  }

  /**
   * @param node is the node to find required files in.
   * @return the list of files required by the node to function.
   */
  List<String> processRequiredFiles(Node node) {
    List<String> files = new ArrayList<>();

    Consumer<Node> fileFinder = n -> files.addAll(n.localFiles().stream().map(f -> {
      try {
        f.setAccessible(true);
        String fPath = (String) f.get(n);
        if (fPath == null) {
          return null;
        }

        File fFile = new File(fPath);
        String fName = fFile.getName();

        // Hash the path to uniquely flatten all files into the output directory
        Path newName = Paths.get(spinnakerStagingPath, Math.abs(fPath.hashCode()) + "-" + fName);
        File parent = newName.toFile().getParentFile();
        if (!parent.exists()) {
          parent.mkdirs();
        } else if (fFile.getParent().equals(parent.toString())) {
          // Don't move paths that are already in the right folder
          return fPath;
        }
        Files.copy(Paths.get(fPath), newName, REPLACE_EXISTING);

        f.set(n, newName.toString());
        return newName.toString();
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to get local files for node " + n.getNodeName(), e);
      } catch (IOException e) {
        throw new HalException(new ProblemBuilder(FATAL, "Failed to backup user file: " + e.getMessage()).build());
      } finally {
        f.setAccessible(false);
      }
    }).filter(Objects::nonNull).collect(Collectors.toList()));
    node.recursiveConsume(fileFinder);

    return files;
  }

  protected  String yamlToString(Object o) {
    return yamlParser.dump(strictObjectMapper.convertValue(o, Map.class));
  }
}
