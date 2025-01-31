package org.jboss.bacon.experimental.impl.generator;

import lombok.extern.slf4j.Slf4j;
import org.jboss.bacon.experimental.impl.config.BuildConfigGeneratorConfig;
import org.jboss.bacon.experimental.impl.dependencies.DependencyResult;
import org.jboss.bacon.experimental.impl.dependencies.Project;
import org.jboss.bacon.experimental.impl.dependencies.ProjectDepthComparator;
import org.jboss.bacon.experimental.impl.projectfinder.EnvironmentResolver;
import org.jboss.bacon.experimental.impl.projectfinder.FoundProject;
import org.jboss.bacon.experimental.impl.projectfinder.FoundProjects;
import org.jboss.da.model.rest.GA;
import org.jboss.da.model.rest.GAV;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.bacon.pig.impl.config.BuildConfig;
import org.jboss.pnc.bacon.pig.impl.mapping.BuildConfigMapping;
import org.jboss.pnc.common.version.SuffixedVersion;
import org.jboss.pnc.common.version.VersionParser;
import org.jboss.pnc.dto.BuildConfiguration;
import org.jboss.pnc.dto.BuildConfigurationRef;
import org.jboss.pnc.dto.BuildConfigurationRevision;
import org.jboss.pnc.dto.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
public class BuildConfigGenerator {
    private final BuildConfigGeneratorConfig config;
    private final EnvironmentResolver environments;
    private final VersionParser versionParser = new VersionParser("redhat");

    private Map<GA, Set<Project>> projectsByGA;

    public BuildConfigGenerator(BuildConfigGeneratorConfig buildConfigGeneratorConfig) {
        this.config = buildConfigGeneratorConfig;
        environments = new EnvironmentResolver(buildConfigGeneratorConfig);
    }

    public List<BuildConfig> generateConfigs(DependencyResult dependencies, FoundProjects foundProjects) {
        projectsByGA = new HashMap<>();
        fillProjectsByGA(dependencies.getTopLevelProjects());
        Map<Project, BuildConfig> buildConfigMap = new TreeMap<>(new ProjectDepthComparator());
        for (Project project : dependencies.getTopLevelProjects()) {
            generateConfig(buildConfigMap, project, foundProjects);
        }
        if (config.isFailGeneratedBuildScript()) {
            log.info(
                    "Some Build Configs might have been generated or modified. Autobuilder appended command `false` to "
                            + "build scripts of such Build Configs and a human should review the Build Configs before "
                            + "removig the command `false`. The generated build-config.yaml can still be run in PNC "
                            + "even with the command `false` present, as it will provide you with information about "
                            + "alignment and the build run.");
        } else {
            log.info(
                    "Some Build Configs might have been generated or modified and a human should review the Build "
                            + "Configs before build outputs from these build configs are used.");
        }
        return new ArrayList<>(buildConfigMap.values());
    }

    private void fillProjectsByGA(Set<Project> projects) {
        for (Project project : projects) {
            GA ga = project.getFirstGAV().getGA();
            projectsByGA.computeIfAbsent(ga, g -> new HashSet<>()).add(project);
            fillProjectsByGA(project.getDependencies());
        }
    }

    private BuildConfig generateConfig(
            Map<Project, BuildConfig> buildConfigMap,
            Project project,
            FoundProjects foundProjects) {
        if (buildConfigMap.containsKey(project)) {
            return buildConfigMap.get(project);
        }
        BuildConfig buildConfig = processProject(project, getFoundForProject(project, foundProjects));
        buildConfigMap.put(project, buildConfig);
        for (Project dep : project.getDependencies()) {
            BuildConfig depBuildConfig = generateConfig(buildConfigMap, dep, foundProjects);
            String name = depBuildConfig.getName();
            buildConfig.getDependencies().add(name);
        }
        Collections.sort(buildConfig.getDependencies());
        return buildConfig;
    }

    public BuildConfig processProject(Project project, FoundProject found) {
        String name = generateBCName(project);
        // Strategy
        if (found.isExactMatch()) {
            BuildConfig buildConfig = copyExisting(found.getBuildConfig(), found.getBuildConfigRevision(), name);
            return updateExactMatch(buildConfig, project);
        } else if (found.isFound()) {
            BuildConfig buildConfig = copyExisting(found.getBuildConfig(), found.getBuildConfigRevision(), name);
            return updateSimilar(buildConfig, project);
        } else {
            return generateNewBuildConfig(project, name);
        }
    }

    private BuildConfig generateNewBuildConfig(Project project, String name) {
        GAV gav = project.getFirstGAV();
        BuildConfig buildConfig = new BuildConfig();
        buildConfig.setBuildType(BuildType.MVN.name());
        buildConfig.setBuildScript(generateBuildScript(isTainted(project)));
        buildConfig.setEnvironmentName(config.getDefaultValues().getEnvironmentName());
        buildConfig.setName(name);
        buildConfig.setProject(gav.getGroupId() + "-" + gav.getArtifactId());
        buildConfig.setDescription("Autobuild genearted config for " + gav);
        String scmUrl = processScmUrl(project.getSourceCodeURL());
        if (scmUrl == null) {
            setPlaceholderSCM(name, buildConfig);
        } else {
            buildConfig.setScmUrl(scmUrl);
            if (project.getSourceCodeRevision() == null) {
                setPlaceholderSCMTag(name, buildConfig);
            } else {
                buildConfig.setScmRevision(project.getSourceCodeRevision());
            }
        }
        return buildConfig;
    }

    private String updateBuildScript(String script, boolean tainted) {
        String commentAutogenerated = "# This build configuration was modified by Autobuilder";
        return renderBuildScript(tainted, commentAutogenerated, script);
    }

    private String generateBuildScript(boolean tainted) {
        String commentAutogenerated = "# This script was autogenerated";
        String defaultBuildCommand = config.getDefaultValues().getBuildScript();
        return renderBuildScript(tainted, commentAutogenerated, defaultBuildCommand);
    }

    private String renderBuildScript(boolean tainted, String commentAutogenerated, String buildCommand) {
        String failCommand = "false";
        StringJoiner sj = new StringJoiner("\n");
        if (!buildCommand.contains(commentAutogenerated)) {
            sj.add(commentAutogenerated);
        }
        sj.add(buildCommand);
        if (tainted) {
            failCommand += " # This build configuration is tainted, you should fix errors reported from Autobuilder before removing this command";
            sj.add(failCommand);
        } else if (config.isFailGeneratedBuildScript()) {
            failCommand += " # This build configuration was modified by Autobuilder and should be reviewed by a human. After review, this line can be removed.";
            sj.add(failCommand);
        }
        return sj.toString();
    }

    private BuildConfig updateExactMatch(BuildConfig buildConfig, Project project) {
        boolean updated = updateAlignParams(buildConfig, true);
        if (updated || isTainted(project)) {
            buildConfig.setBuildScript(updateBuildScript(buildConfig.getBuildScript(), isTainted(project)));
        }
        return buildConfig;
    }

    private boolean isTainted(Project project) {
        return project.isCutDepenendecy() // Cut dependency means that the project will miss linked dependencies
                || isThereConflictingProject(project); // Conflict means there are multiple BCs building the same thing
    }

    private BuildConfig updateSimilar(BuildConfig buildConfig, Project project) {
        updateAlignParams(buildConfig, false);
        if (project.getSourceCodeRevision() == null) {
            setPlaceholderSCMTag(buildConfig.getName(), buildConfig);
        } else {
            buildConfig.setScmRevision(project.getSourceCodeRevision());
        }
        buildConfig.setBuildScript(updateBuildScript(buildConfig.getBuildScript(), isTainted(project)));
        return buildConfig;
    }

    private boolean updateAlignParams(BuildConfig buildConfig, boolean keepVersionOverride) {
        Set<String> originalAlignParams = buildConfig.getAlignmentParameters();
        Set<String> alignmentParameters = originalAlignParams.stream()
                .map(p -> BuildConfigGenerator.removeOverride(p, keepVersionOverride))
                .filter(p -> !p.isBlank())
                .collect(Collectors.toSet());
        if (alignmentParameters.equals(originalAlignParams)) {
            return false;
        } else {
            buildConfig.setAlignmentParameters(alignmentParameters);
            return true;
        }
    }

    static String removeOverride(String parameter, boolean keepVersionOverride) {
        return ArgumentTokenizer.tokenize(parameter)
                .stream()
                .filter(s -> !s.contains("dependencyOverride"))
                .filter(s -> !s.contains("manipulation.disable=true"))
                .filter(s -> keepVersionOverride || !s.contains("versionOverride"))
                .collect(Collectors.joining(" "));
    }

    private String generateBCName(Project project) {
        GAV gav = project.getFirstGAV();
        String version = resolveVersionForName(project, gav);
        return gav.getGroupId() + "-" + gav.getArtifactId() + "-" + version + "-AUTOBUILD";
    }

    private String resolveVersionForName(Project project, GAV gav) {
        SuffixedVersion version = versionParser.parse(gav.getVersion());
        if (!version.isSuffixed()) {
            return gav.getVersion();
        }
        if (isThereConflictingProject(project)) {
            log.error("Project " + gav + " has a duplicate project without the redhat suffix.");
            return gav.getVersion();
        }
        return version.unsuffixedVersion();
    }

    private boolean isThereConflictingProject(Project project) {
        GAV gav = project.getFirstGAV();
        GA ga = gav.getGA();
        SuffixedVersion version = versionParser.parse(gav.getVersion());
        Set<Project> projects = projectsByGA.get(ga);
        for (Project other : projects) {
            if (other.equals(project)) {
                continue;
            }
            SuffixedVersion otherVersion = versionParser.parse(other.getFirstGAV().getVersion());
            if (otherVersion.unsuffixedVersion().equals(version.unsuffixedVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the project has a "well-defined" build config already. Well-defined Build Config is such that
     * either: - Has no dependencies and no dependencies need to be setup - Has all dependencies that need to be setup
     * already setup AND all the dependencies are well-defined Build Configs.
     */
    private boolean isWellDefined(Project project, FoundProject found) {
        if (!found.isFound()) {
            return false; // Build config doesn't exist, so it's not defined at all
        }
        Map<String, BuildConfigurationRef> dependencies = found.getBuildConfig().getDependencies();
        if (project.getDependencies().isEmpty()) {
            return dependencies.isEmpty();
        } else {
            Set<String> dependencyIds = new HashSet<>();
            for (Project dep : project.getDependencies()) {
                FoundProject depFound = getFoundForProject(dep, null /* TODO */);
                if (!depFound.isFound()) {
                    return false;
                }
                String dependencyId = depFound.getBuildConfig().getId();
                if (!dependencies.containsKey(dependencyId)) {
                    return false;
                }
                dependencyIds.add(dependencyId);
                if (!isWellDefined(dep, depFound)) {
                    return false;
                }
            }
            return dependencyIds.size() == dependencies.size();
        }
    }

    private FoundProject getFoundForProject(Project project, FoundProjects founds) {
        return founds.getFoundProjects().stream().filter(fp -> fp.getGavs().equals(project.getGavs())).findAny().get();
    }

    public BuildConfig copyExisting(BuildConfiguration bc, BuildConfigurationRevision bcr, String name) {
        Environment env = environments.resolve(bcr.getEnvironment());
        boolean useEnvironmentName = shouldUseEnvironmentName(name, env);

        BuildConfigMapping.GeneratorOptions opts = BuildConfigMapping.GeneratorOptions.builder()
                .nameOverride(Optional.of(name))
                .useEnvironmentName(useEnvironmentName)
                .build();
        BuildConfig buildConfig = BuildConfigMapping.toBuildConfig(bc, bcr, opts);
        if (!name.equals(bc.getName())) {
            String copyMessage = "# Autobuilder copied this Build Config from BC #" + bcr.getId() + " rev "
                    + bcr.getRev();
            buildConfig.setBuildScript(copyMessage + "\n" + buildConfig.getBuildScript());
        }
        setEnvironment(buildConfig, env, useEnvironmentName);
        String scmUrl = processScmUrl(buildConfig.getScmUrl());
        if (scmUrl == null) {
            setPlaceholderSCM(name, buildConfig);
        } else {
            buildConfig.setScmUrl(scmUrl);
        }
        buildConfig.getDependencies().clear();
        return buildConfig;
    }

    private static void setEnvironment(BuildConfig buildConfig, Environment env, boolean useName) {
        if (useName) {
            if (!env.getName().equals(buildConfig.getEnvironmentName())) {
                log.info(
                        "Replacing environmentName for " + buildConfig.getName() + ": "
                                + buildConfig.getEnvironmentName() + " -> " + env.getName());
                buildConfig.setEnvironmentName(env.getName());
            }
        } else {
            if (!env.getSystemImageId().equals(buildConfig.getSystemImageId())) {
                log.info(
                        "Replacing systemImageId for " + buildConfig.getName() + ": " + buildConfig.getSystemImageId()
                                + " -> " + env.getSystemImageId());
                buildConfig.setSystemImageId(env.getSystemImageId());
            }
        }
    }

    private boolean shouldUseEnvironmentName(String name, Environment environment) {
        if (!environment.isDeprecated()) {
            return true;
        }
        if (config.isAllowDeprecatedEnvironments()) {
            log.warn(
                    "Environment " + environment.getName() + " for build config " + name
                            + " is deprecated, using it anyway (with system image id " + environment.getSystemImageId()
                            + ")");
            return false;
        } else {
            log.warn(
                    "Environment " + environment.getName() + " for build config " + name
                            + " is deprecated, you will have to update it manually");
            return true;
        }
    }

    private void setPlaceholderSCM(String name, BuildConfig buildConfig) {
        log.warn("Using placeholder SCM url for Build Config {}", name);
        buildConfig.setScmUrl(config.getDefaultValues().getScmUrl());
        buildConfig.setScmRevision(config.getDefaultValues().getScmRevision());
    }

    private void setPlaceholderSCMTag(String name, BuildConfig buildConfig) {
        log.warn("Using placeholder SCM tag for Build Config {}", name);
        buildConfig.setScmRevision(config.getDefaultValues().getScmRevision());
    }

    private String processScmUrl(String originalUrl) {
        if (originalUrl == null) {
            return null;
        }
        for (String key : config.getScmReplaceWithPlaceholder()) {
            if (originalUrl.contains(key)) {
                return null;
            }
        }
        String updatedUrl = originalUrl;
        for (Map.Entry<String, String> e : config.getScmPattern().entrySet()) {
            updatedUrl = updatedUrl.replace(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : config.getScmMapping().entrySet()) {
            String key = e.getKey();
            if (updatedUrl.contains(key)) {
                updatedUrl = e.getValue();
                break;
            }
            if (key.endsWith(".git") && updatedUrl.endsWith(key.substring(0, key.length() - 4))) {
                updatedUrl = e.getValue();
                break;
            }
        }
        if (!originalUrl.equals(updatedUrl)) {
            log.debug("Updated SCM URL from {} to {}", originalUrl, updatedUrl);
        }
        return updatedUrl;
    }

}
