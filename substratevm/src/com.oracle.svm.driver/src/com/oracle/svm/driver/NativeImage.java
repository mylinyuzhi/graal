/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.driver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.options.OptionKey;
import org.graalvm.nativeimage.Platform;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.heap.PhysicalMemory;
import com.oracle.svm.core.jdk.LocalizationSupport;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.posix.PosixExecutableName;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.driver.MacroOption.EnabledOption;
import com.oracle.svm.driver.MacroOption.MacroOptionKind;
import com.oracle.svm.driver.MacroOption.Registry;
import com.oracle.svm.graal.hosted.GraalFeature;
import com.oracle.svm.hosted.FeatureHandler;
import com.oracle.svm.hosted.NativeImageOptions;
import com.oracle.svm.hosted.image.AbstractBootImage.NativeImageKind;
import com.oracle.svm.hosted.substitute.DeclarativeSubstitutionProcessor;
import com.oracle.svm.jni.hosted.JNIFeature;
import com.oracle.svm.reflect.hosted.ReflectionFeature;

class NativeImage {

    private static String getPlatform() {
        if (Platform.includedIn(Platform.DARWIN_AMD64.class)) {
            return "darwin-amd64";
        }
        if (Platform.includedIn(Platform.LINUX_AMD64.class)) {
            return "linux-amd64";
        }
        throw VMError.shouldNotReachHere();
    }

    static final String platform = getPlatform();
    static final String svmVersion = System.getProperty("substratevm.version");

    private static String getGraalVMVersion() {
        String tmpGraalVmVersion = System.getProperty("org.graalvm.version");
        if (tmpGraalVmVersion == null) {
            tmpGraalVmVersion = System.getProperty("graalvm.version");
        }
        if (tmpGraalVmVersion == null) {
            throw new RuntimeException("Could not find GraalVM version in graalvm.version or org.graalvm.version");
        }
        return tmpGraalVmVersion;
    }

    static final String graalvmVersion = getGraalVMVersion();

    static String getResource(String resourceName) {
        try (InputStream input = NativeImage.class.getResourceAsStream(resourceName)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String resourceString = reader.lines().collect(Collectors.joining("\n"));
            return resourceString.replace("%pathsep%", File.pathSeparator);
        } catch (IOException e) {
            VMError.shouldNotReachHere(e);
        }
        return null;
    }

    private static final String usageText = getResource("/Usage.txt");

    abstract static class OptionHandler<T extends NativeImage> {
        protected final T nativeImage;

        OptionHandler(T nativeImage) {
            this.nativeImage = nativeImage;
        }

        abstract boolean consume(Queue<String> args);
    }

    final APIOptionHandler apiOptionHandler;

    static final String oH = "-H:";
    static final String oR = "-R:";

    static final String enableRuntimeAssertions = "+" + SubstrateOptions.RuntimeAssertions.getName();
    static final String enablePrintFlags = SubstrateOptions.PrintFlags.getName() + "=";

    private static <T> String oH(OptionKey<T> option) {
        return oH + option.getName() + "=";
    }

    static final String oHClass = oH(NativeImageOptions.Class);
    static final String oHName = oH(NativeImageOptions.Name);
    static final String oHPath = oH(SubstrateOptions.Path);
    static final String oHKind = oH(NativeImageOptions.Kind);
    static final String oHCLibraryPath = oH(SubstrateOptions.CLibraryPath);
    static final String oHOptimize = oH(SubstrateOptions.Optimize);
    static final String oHDebug = oH + "Debug=";

    /* List arguments */
    static final String oHFeatures = oH(FeatureHandler.Options.Features);
    static final String oHSubstitutionFiles = oH(DeclarativeSubstitutionProcessor.Options.SubstitutionFiles);
    static final String oHSubstitutionResources = oH(DeclarativeSubstitutionProcessor.Options.SubstitutionResources);
    static final String oHIncludeResourceBundles = oH(LocalizationSupport.Options.IncludeResourceBundles);
    static final String oHReflectionConfigurationFiles = oH(ReflectionFeature.Options.ReflectionConfigurationFiles);
    static final String oHReflectionConfigurationResources = oH(ReflectionFeature.Options.ReflectionConfigurationResources);
    static final String oHJNIConfigurationFiles = oH(JNIFeature.Options.JNIConfigurationFiles);
    static final String oHJNIConfigurationResources = oH(JNIFeature.Options.JNIConfigurationResources);
    static final String oHInterfacesForJNR = oH + "InterfacesForJNR=";

    static final String oHMaxRuntimeCompileMethods = oH(GraalFeature.Options.MaxRuntimeCompileMethods);
    static final String oHInspectServerContentPath = oH(PointstoOptions.InspectServerContentPath);
    static final String oDPolyglotLauncherClasses = "-Dcom.oracle.graalvm.launcher.launcherclasses=";
    static final String oDLauncherClasspath = "-Dorg.graalvm.launcher.classpath=";

    static final String oXmx = "-Xmx";
    static final String oXms = "-Xms";

    private static final String pKeyNativeImageArgs = "NativeImageArgs";

    private final LinkedHashSet<String> imageBuilderArgs = new LinkedHashSet<>();
    private final LinkedHashSet<Path> imageBuilderClasspath = new LinkedHashSet<>();
    private final LinkedHashSet<Path> imageBuilderBootClasspath = new LinkedHashSet<>();
    private final LinkedHashSet<String> imageBuilderJavaArgs = new LinkedHashSet<>();
    private final LinkedHashSet<Path> imageClasspath = new LinkedHashSet<>();
    private final LinkedHashSet<Path> imageProvidedClasspath = new LinkedHashSet<>();
    private final LinkedHashSet<String> customJavaArgs = new LinkedHashSet<>();
    private final LinkedHashSet<String> customImageBuilderArgs = new LinkedHashSet<>();
    private final LinkedHashSet<Path> customImageClasspath = new LinkedHashSet<>();
    private final ArrayList<OptionHandler<? extends NativeImage>> optionHandlers = new ArrayList<>();

    private final Path executablePath;
    private final Path workDir;
    private final Path rootDir;
    private final Path homeDir;
    private final Map<String, String> userConfigProperties = new HashMap<>();

    private boolean verbose = Boolean.valueOf(System.getenv("VERBOSE_GRAALVM_LAUNCHERS"));
    private boolean dryRun = false;

    final Registry optionRegistry;
    private LinkedHashSet<EnabledOption> enabledLanguages;

    protected NativeImage() {
        workDir = Paths.get(".").toAbsolutePath().normalize();
        assert workDir != null;
        executablePath = Paths.get((String) Compiler.command(new Object[]{PosixExecutableName.getKey()}));
        assert executablePath != null;
        Path binDir = executablePath.getParent();
        Path rootDirCandidate = binDir.getParent();
        if (rootDirCandidate.endsWith(platform)) {
            rootDirCandidate = rootDirCandidate.getParent();
        }
        rootDir = rootDirCandidate;
        assert rootDir != null;
        String homeDirString = System.getProperty("user.home");
        homeDir = Paths.get(homeDirString);
        assert homeDir != null;

        String configFileEnvVarKey = "NATIVE_IMAGE_CONFIG_FILE";
        String configFile = System.getenv(configFileEnvVarKey);
        if (configFile != null && !configFile.isEmpty()) {
            try {
                userConfigProperties.putAll(loadProperties(canonicalize(Paths.get(configFile))));
            } catch (NativeImageError | Exception e) {
                showError("Invalid environment variable " + configFileEnvVarKey, e);
            }
        }

        // Default javaArgs needed for image building
        addImageBuilderJavaArgs("-server", "-d64", "-noverify");
        addImageBuilderJavaArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCI");

        // Same as GRAAL_COMPILER_FLAGS in mx.substratevm/mx_substratevm.py
        int ciCompilerCount = Runtime.getRuntime().availableProcessors() <= 4 ? 2 : 4;
        addImageBuilderJavaArgs("-XX:-UseJVMCIClassLoader", "-XX:+UseJVMCICompiler", "-Dgraal.CompileGraalWithC1Only=false", "-XX:CICompilerCount=" + ciCompilerCount);
        addImageBuilderJavaArgs("-Dgraal.VerifyGraalGraphs=false", "-Dgraal.VerifyGraalGraphEdges=false", "-Dgraal.VerifyGraalPhasesSize=false", "-Dgraal.VerifyPhases=false");

        addImageBuilderJavaArgs("-Dgraal.EagerSnippets=true");

        addImageBuilderJavaArgs("-Xss10m");
        addImageBuilderJavaArgs(oXms + getXmsValue());
        addImageBuilderJavaArgs(oXmx + getXmxValue(1));

        addImageBuilderJavaArgs("-Duser.country=US", "-Duser.language=en");

        addImageBuilderJavaArgs("-Dsubstratevm.version=" + svmVersion);
        if (graalvmVersion != null) {
            addImageBuilderJavaArgs("-Dgraalvm.version=" + graalvmVersion);
            addImageBuilderJavaArgs("-Dorg.graalvm.version=" + graalvmVersion);
        }

        addImageBuilderJavaArgs("-Dcom.oracle.graalvm.isaot=true");

        // Generate images into the current directory
        addImageBuilderArg(oHPath + workDir);

        /* Discover supported MacroOptions */
        optionRegistry = new MacroOption.Registry(canonicalize(getRootDir()));

        /* Default handler needs to be fist */
        registerOptionHandler(new DefaultOptionHandler(this));
        apiOptionHandler = new APIOptionHandler(this);
        registerOptionHandler(apiOptionHandler);
        registerOptionHandler(new MacroOptionHandler(this));
    }

    void addMacroOptionRoot(Path configDir) {
        optionRegistry.addMacroOptionRoot(canonicalize(configDir));
    }

    protected void registerOptionHandler(OptionHandler<? extends NativeImage> handler) {
        optionHandlers.add(handler);
    }

    protected Path getRootDir() {
        return rootDir;
    }

    protected Path getHomeDir() {
        return homeDir;
    }

    protected Map<String, String> getUserConfigProperties() {
        return userConfigProperties;
    }

    protected Path getUserConfigDir() {
        return getHomeDir().resolve(".native-image");
    }

    protected static void ensureDirectoryExists(Path dir) {
        if (Files.exists(dir)) {
            if (!Files.isDirectory(dir)) {
                throw showError("File " + dir + " is not a directory");
            }
        } else {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw showError("Could not create directory " + dir);
            }
        }
    }

    private void prepareImageBuildArgs() {
        Path svmDir = getRootDir().resolve(Paths.get("lib", "svm"));
        getJars(svmDir.resolve("builder")).forEach(this::addImageBuilderClasspath);
        getJars(svmDir).forEach(this::addImageProvidedClasspath);
        Path clibrariesDir = svmDir.resolve("clibraries").resolve(platform);
        addImageBuilderArg(oHCLibraryPath + clibrariesDir);
        if (Files.isDirectory(svmDir.resolve("inspect"))) {
            addImageBuilderArg(oHInspectServerContentPath + svmDir.resolve("inspect"));
        }

        Path jvmciDir = getRootDir().resolve(Paths.get("lib", "jvmci"));
        getJars(jvmciDir).forEach((Consumer<? super Path>) this::addImageBuilderClasspath);
        try {
            addImageBuilderJavaArgs(Files.list(jvmciDir)
                            .filter(f -> f.getFileName().toString().toLowerCase().endsWith("graal.jar"))
                            .map(this::canonicalize)
                            .map(Path::toString)
                            .collect(Collectors.joining(":", "-Djvmci.class.path.append=", "")));
        } catch (IOException e) {
            showError("Unable to use jar-files from directory " + jvmciDir, e);
        }

        Path bootDir = getRootDir().resolve(Paths.get("lib", "boot"));
        getJars(bootDir).forEach((Consumer<? super Path>) this::addImageBuilderBootClasspath);
    }

    private void completeOptionArgs() {
        /* Determine if truffle is needed- any MacroOption of kind Language counts */
        enabledLanguages = optionRegistry.getEnabledOptions(MacroOptionKind.Language);
        for (EnabledOption enabledOption : optionRegistry.getEnabledOptions()) {
            if (!MacroOptionKind.Language.equals(enabledOption.getOption().kind) && enabledOption.getProperty("LauncherClass") != null) {
                /* Also identify non-Language MacroOptions as Language if LauncherClass is set */
                enabledLanguages.add(enabledOption);
            }
        }

        /* Create a polyglot image if we have more than one LauncherClass. */
        if (getLauncherClasses().limit(2).count() > 1) {
            /* Use polyglot as image name if not defined on command line */
            if (customImageBuilderArgs.stream().noneMatch(arg -> arg.startsWith(oHName))) {
                replaceArg(imageBuilderArgs, oHName, "polyglot");
            }
            if (customImageBuilderArgs.stream().noneMatch(arg -> arg.startsWith(oHClass))) {
                /* and the PolyglotLauncher as main class if not defined on command line */
                replaceArg(imageBuilderArgs, oHClass, "org.graalvm.launcher.PolyglotLauncher");
            }
        }

        /* Provide more memory for image building if we have more than one language. */
        if (enabledLanguages.size() > 1) {
            long baseMemRequirements = SubstrateOptionsParser.parseLong("4g");
            long memRequirements = baseMemRequirements + enabledLanguages.size() * SubstrateOptionsParser.parseLong("1g");
            /* Add mem-requirement for polyglot building - gets further consolidated (use max) */
            addImageBuilderJavaArgs(oXmx + memRequirements);
        }

        consolidateListArgs(imageBuilderJavaArgs, "-Dpolyglot.engine.PreinitializeContexts=", ",", Function.identity());
    }

    private Stream<String> getLanguageLauncherClasses() {
        return optionRegistry.getEnabledOptionsStream(MacroOptionKind.Language)
                        .map(lang -> lang.getProperty("LauncherClass"))
                        .filter(Objects::nonNull).distinct();
    }

    private Stream<String> getLauncherClasses() {
        return optionRegistry.getEnabledOptionsStream(MacroOptionKind.Language, MacroOptionKind.Tool)
                        .map(lang -> lang.getProperty("LauncherClass"))
                        .filter(Objects::nonNull).distinct();
    }

    private Stream<String> getRelativeLauncherClassPath() {
        return optionRegistry.getEnabledOptionsStream(MacroOptionKind.Language, MacroOptionKind.Tool)
                        .map(lang -> lang.getProperty("LauncherClassPath"))
                        .filter(Objects::nonNull).flatMap(Pattern.compile(":", Pattern.LITERAL)::splitAsStream);
    }

    private Stream<Path> getAbsoluteLauncherClassPath() {
        return getRelativeLauncherClassPath().map(s -> Paths.get(s.replace('/', File.separatorChar))).map(p -> getRootDir().resolve(p));
    }

    protected static String consolidateSingleValueArg(Collection<String> args, String argPrefix) {
        BiFunction<String, String, String> takeLast = (a, b) -> b;
        return consolidateArgs(args, argPrefix, Function.identity(), Function.identity(), () -> null, takeLast);
    }

    protected static boolean replaceArg(Collection<String> args, String argPrefix, String argSuffix) {
        boolean elementsRemoved = args.removeIf(arg -> arg.startsWith(argPrefix));
        args.add(argPrefix + argSuffix);
        return elementsRemoved;
    }

    private static <T> T consolidateArgs(Collection<String> args, String argPrefix,
                    Function<String, T> fromSuffix, Function<T, String> toSuffix,
                    Supplier<T> init, BiFunction<T, T, T> combiner) {
        T consolidatedValue = null;
        boolean needsConsolidate = false;
        for (String arg : args) {
            if (arg.startsWith(argPrefix)) {
                if (consolidatedValue == null) {
                    consolidatedValue = init.get();
                } else {
                    needsConsolidate = true;
                }
                consolidatedValue = combiner.apply(consolidatedValue, fromSuffix.apply(arg.substring(argPrefix.length())));
            }
        }
        if (consolidatedValue != null && needsConsolidate) {
            replaceArg(args, argPrefix, toSuffix.apply(consolidatedValue));
        }
        return consolidatedValue;
    }

    private static LinkedHashSet<String> collectListArgs(Collection<String> args, String argPrefix, String delimiter) {
        LinkedHashSet<String> allEntries = new LinkedHashSet<>();
        for (String arg : args) {
            if (arg.startsWith(argPrefix)) {
                String argEntriesRaw = arg.substring(argPrefix.length());
                if (!argEntriesRaw.isEmpty()) {
                    allEntries.addAll(Arrays.asList(argEntriesRaw.split(delimiter)));
                }
            }
        }
        return allEntries;
    }

    private static void consolidateListArgs(Collection<String> args, String argPrefix, String delimiter, Function<String, String> mapFunc) {
        LinkedHashSet<String> allEntries = collectListArgs(args, argPrefix, delimiter);
        if (!allEntries.isEmpty()) {
            replaceArg(args, argPrefix, allEntries.stream().map(mapFunc).collect(Collectors.joining(delimiter)));
        }
    }

    private void completeImageBuildArgs(String[] args) {
        List<String> leftoverArgs = processNativeImageArgs(args);

        completeOptionArgs();

        /* If no customImageClasspath was specified put "." on classpath */
        if (customImageClasspath.isEmpty()) {
            addImageProvidedClasspath(Paths.get("."));
        } else {
            imageClasspath.addAll(customImageClasspath);
        }

        /* Perform JavaArgs consolidation - take the maximum of -Xmx, minimum of -Xms */
        Long xmxValue = consolidateArgs(imageBuilderJavaArgs, oXmx, SubstrateOptionsParser::parseLong, String::valueOf, () -> 0L, Math::max);
        Long xmsValue = consolidateArgs(imageBuilderJavaArgs, oXms, SubstrateOptionsParser::parseLong, String::valueOf, () -> SubstrateOptionsParser.parseLong(getXmsValue()), Math::max);
        if (WordFactory.unsigned(xmsValue).aboveThan(WordFactory.unsigned(xmxValue))) {
            replaceArg(imageBuilderJavaArgs, oXms, Long.toUnsignedString(xmxValue));
        }

        /* After JavaArgs consolidation add the user provided JavaArgs */
        addImageBuilderJavaArgs(customJavaArgs.toArray(new String[0]));

        /* Perform option consolidation of imageBuilderArgs */
        Function<String, String> canonicalizedPathStr = s -> canonicalize(Paths.get(s)).toString();
        consolidateArgs(imageBuilderArgs, oHMaxRuntimeCompileMethods, Integer::parseInt, String::valueOf, () -> 0, Integer::sum);
        consolidateListArgs(imageBuilderArgs, oHCLibraryPath, ",", canonicalizedPathStr);
        consolidateListArgs(imageBuilderArgs, oHSubstitutionFiles, ",", canonicalizedPathStr);
        consolidateListArgs(imageBuilderArgs, oHSubstitutionResources, ",", Function.identity());
        consolidateListArgs(imageBuilderArgs, oHIncludeResourceBundles, ",", Function.identity());
        consolidateListArgs(imageBuilderArgs, oHInterfacesForJNR, ",", Function.identity());
        consolidateListArgs(imageBuilderArgs, oHReflectionConfigurationFiles, ",", canonicalizedPathStr);
        consolidateListArgs(imageBuilderArgs, oHReflectionConfigurationResources, ",", Function.identity());
        consolidateListArgs(imageBuilderArgs, oHJNIConfigurationFiles, ",", canonicalizedPathStr);
        consolidateListArgs(imageBuilderArgs, oHJNIConfigurationResources, ",", Function.identity());
        consolidateListArgs(imageBuilderArgs, oHFeatures, ",", Function.identity());

        BiFunction<String, String, String> takeLast = (a, b) -> b;
        consolidateArgs(imageBuilderArgs, oHPath, Function.identity(), canonicalizedPathStr, () -> null, takeLast);
        consolidateArgs(imageBuilderArgs, oHName, Function.identity(), Function.identity(), () -> null, takeLast);
        String mainClass = consolidateSingleValueArg(imageBuilderArgs, oHClass);
        String imageKind = consolidateSingleValueArg(imageBuilderArgs, oHKind);
        boolean buildExecutable = !NativeImageKind.SHARED_LIBRARY.name().equals(imageKind);
        boolean printFlags = imageBuilderArgs.stream().anyMatch(arg -> arg.contains(enablePrintFlags));

        if (buildExecutable && !printFlags) {
            List<String> extraImageArgs = new ArrayList<>();
            ListIterator<String> leftoverArgsItr = leftoverArgs.listIterator();
            while (leftoverArgsItr.hasNext()) {
                String leftoverArg = leftoverArgsItr.next();
                if (!leftoverArg.startsWith("-")) {
                    leftoverArgsItr.remove();
                    extraImageArgs.add(leftoverArg);
                }
            }

            /* Main-class from customImageBuilderArgs counts as explicitMainClass */
            boolean explicitMainClass = customImageBuilderArgs.stream().anyMatch(arg -> arg.startsWith(oHClass));

            if (extraImageArgs.isEmpty()) {
                if (mainClass == null || mainClass.isEmpty()) {
                    showError("Please specify class containing the main entry point method. (see --help)");
                }
            } else {
                /* extraImageArgs main-class overrules previous main-class specification */
                explicitMainClass = true;
                mainClass = extraImageArgs.remove(0);
                replaceArg(imageBuilderArgs, oHClass, mainClass);
            }

            if (extraImageArgs.isEmpty()) {
                /* No explicit image name, define image name by other means */
                if (customImageBuilderArgs.stream().noneMatch(arg -> arg.startsWith(oHName))) {
                    /* Also no explicit image name given as customImageBuilderArgs */
                    if (explicitMainClass) {
                        /* Use main-class lower case as image name */
                        replaceArg(imageBuilderArgs, oHName, mainClass.toLowerCase());
                    } else if (imageBuilderArgs.stream().noneMatch(arg -> arg.startsWith(oHName))) {
                        /* Although very unlikely, report missing image-name if needed. */
                        throw showError("Missing image-name. Use " + oHName + "<imagename> to provide one.");
                    }
                }
            } else {
                /* extraImageArgs executable name overrules previous specification */
                replaceArg(imageBuilderArgs, oHName, extraImageArgs.remove(0));
            }
        }

        boolean isGraalVMLauncher = false;
        if ("org.graalvm.launcher.PolyglotLauncher".equals(mainClass) && consolidateSingleValueArg(imageBuilderJavaArgs, oDPolyglotLauncherClasses) == null) {
            /* Collect the launcherClasses for enabledLanguages. */
            addImageBuilderJavaArgs(oDPolyglotLauncherClasses + getLanguageLauncherClasses().collect(Collectors.joining(",")));
            isGraalVMLauncher = true;
        }

        if (!isGraalVMLauncher && mainClass != null) {
            isGraalVMLauncher = getLauncherClasses().anyMatch(mainClass::equals);
        }

        if (isGraalVMLauncher) {
            showVerboseMessage(verbose || dryRun, "Automatically appending LauncherClassPath");
            getAbsoluteLauncherClassPath().forEach(p -> {
                if (!Files.isRegularFile(p)) {
                    showWarning(String.format("Ignoring '%s' from LauncherClassPath: it does not exist or is not a regular file", p));
                } else {
                    addImageClasspath(p);
                }
            });
            addImageClasspath(getRootDir().resolve(Paths.get("lib", "graalvm", "launcher-common.jar")));
        }

        if (!leftoverArgs.isEmpty()) {
            String prefix = "Unrecognized option" + (leftoverArgs.size() == 1 ? ": " : "s: ");
            showError(leftoverArgs.stream().collect(Collectors.joining(", ", prefix, "")));
        }

        LinkedHashSet<Path> finalImageClasspath = new LinkedHashSet<>(imageBuilderBootClasspath);
        finalImageClasspath.addAll(imageBuilderClasspath);
        finalImageClasspath.addAll(imageProvidedClasspath);
        finalImageClasspath.addAll(imageClasspath);

        if (isGraalVMLauncher && collectListArgs(imageBuilderJavaArgs, oDLauncherClasspath, File.pathSeparator).isEmpty()) {
            StringJoiner sj = new StringJoiner(File.pathSeparator, oDLauncherClasspath, "");
            for (Path p : imageClasspath) {
                Path canonical = canonicalize(p);
                if (!canonical.startsWith(rootDir)) {
                    System.err.println(String.format("WARNING: ignoring '%s' while building launcher classpath: it does not live under the GraalVM root (%s)", canonical, rootDir));
                    continue;
                }
                sj.add(Paths.get("jre").resolve(rootDir.relativize(canonical)).toString());
            }
            addImageBuilderJavaArgs(sj.toString());
        }

        buildImage(imageBuilderJavaArgs, imageBuilderBootClasspath, imageBuilderClasspath, imageBuilderArgs, finalImageClasspath);
    }

    protected void buildImage(LinkedHashSet<String> javaArgs, LinkedHashSet<Path> bcp, LinkedHashSet<Path> cp, LinkedHashSet<String> imageArgs, LinkedHashSet<Path> imagecp) {
        /* Construct ProcessBuilder command from final arguments */
        ProcessBuilder pb = new ProcessBuilder();
        List<String> command = pb.command();
        command.add(getJavaHome().resolve("bin/java").toString());
        if (!bcp.isEmpty()) {
            command.add(bcp.stream().map(Path::toString).collect(Collectors.joining(":", "-Xbootclasspath/a:", "")));
        }
        command.addAll(Arrays.asList("-cp", cp.stream().map(Path::toString).collect(Collectors.joining(":"))));
        command.addAll(javaArgs);
        command.add("com.oracle.svm.hosted.NativeImageGeneratorRunner");
        command.addAll(Arrays.asList("-imagecp", imagecp.stream().map(Path::toString).collect(Collectors.joining(":"))));
        command.addAll(imageArgs);

        showVerboseMessage(verbose || dryRun, "Executing [");
        showVerboseMessage(verbose || dryRun, command.stream().collect(Collectors.joining(" \\\n")));
        showVerboseMessage(verbose || dryRun, "]");

        if (!dryRun) {
            try {
                Process p = pb.inheritIO().start();
                int exitStatus = p.waitFor();
                if (exitStatus != 0) {
                    throw showError("Image building with exit status " + exitStatus);
                }
            } catch (IOException | InterruptedException e) {
                throw showError(e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        try {
            NativeImage nativeImage = new NativeImageServer();

            if (args.length == 0) {
                nativeImage.showMessage(usageText);
                System.exit(0);
            }

            nativeImage.prepareImageBuildArgs();
            nativeImage.completeImageBuildArgs(args);
        } catch (NativeImageError e) {
            boolean verbose = Boolean.valueOf(System.getenv("VERBOSE_GRAALVM_LAUNCHERS"));
            NativeImage.show(System.err::println, "Error: " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                NativeImage.show(System.err::println, "Caused by: " + cause);
                cause = cause.getCause();
            }
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    Path canonicalize(Path path) {
        Path absolutePath = path.isAbsolute() ? path : workDir.resolve(path);
        boolean hasWildcard = absolutePath.endsWith("*");
        if (hasWildcard) {
            absolutePath = absolutePath.getParent();
        }
        try {
            Path realPath = absolutePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isReadable(realPath)) {
                showError("Path entry " + path.toString() + " is not readable");
            }
            if (hasWildcard) {
                if (!Files.isDirectory(realPath)) {
                    showError("Path entry with wildcard " + path.toString() + " is not a directory");
                }
                realPath = realPath.resolve("*");
            }
            return realPath;
        } catch (IOException e) {
            throw showError("Invalid Path entry " + path.toString(), e);
        }
    }

    Path getJavaHome() {
        Path javaHomePath = getRootDir().getParent();
        Path binJava = Paths.get("bin", "java");
        if (Files.isExecutable(javaHomePath.resolve(binJava))) {
            return javaHomePath;
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null) {
            throw showError("Environment variable JAVA_HOME is not set");
        }
        javaHomePath = Paths.get(javaHome);
        if (!Files.isExecutable(javaHomePath.resolve(binJava))) {
            throw showError("Environment variable JAVA_HOME does not refer to a directory with a " + binJava + " executable");
        }
        return javaHomePath;
    }

    void addImageBuilderClasspath(Path classpath) {
        imageBuilderClasspath.add(canonicalize(classpath));
    }

    void addImageBuilderBootClasspath(Path classpath) {
        imageBuilderBootClasspath.add(canonicalize(classpath));
    }

    void addImageBuilderJavaArgs(String... javaArgs) {
        addImageBuilderJavaArgs(Arrays.asList(javaArgs));
    }

    void addImageBuilderJavaArgs(Collection<String> javaArgs) {
        imageBuilderJavaArgs.addAll(javaArgs);
    }

    void addImageBuilderArg(String arg) {
        imageBuilderArgs.remove(arg);
        imageBuilderArgs.add(arg);
    }

    void addImageClasspath(Path classpath) {
        imageClasspath.add(canonicalize(classpath));
    }

    /**
     * For adding classpath elements that are not normally on the classpath in the Java version: svm
     * jars, truffle jars etc.
     */
    void addImageProvidedClasspath(Path classpath) {
        imageProvidedClasspath.add(canonicalize(classpath));
    }

    void addCustomImageClasspath(Path classpath) {
        customImageClasspath.add(canonicalize(classpath));
    }

    void addCustomJavaArgs(String javaArg) {
        customJavaArgs.add(javaArg);
    }

    void addCustomImageBuilderArgs(String arg) {
        addImageBuilderArg(arg);
        customImageBuilderArgs.add(arg);
    }

    void setVerbose(boolean val) {
        verbose = val;
    }

    boolean isVerbose() {
        return verbose;
    }

    protected void setDryRun(boolean val) {
        dryRun = val;
    }

    boolean isDryRun() {
        return dryRun;
    }

    void showVerboseMessage(boolean show, String message) {
        if (show) {
            show(System.out::println, message);
        }
    }

    void showMessage(String message) {
        show(System.out::println, message);
    }

    void showNewline() {
        System.out.println();
    }

    void showMessagePart(String message) {
        show(s -> {
            System.out.print(s);
            System.out.flush();
        }, message);
    }

    void showWarning(String message) {
        show(System.err::println, "Warning: " + message);
    }

    @SuppressWarnings("serial")
    static final class NativeImageError extends Error {
        private NativeImageError(String message) {
            super(message);
        }

        private NativeImageError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static Error showError(String message) {
        throw new NativeImageError(message);
    }

    static Error showError(String message, Throwable cause) {
        throw new NativeImageError(message, cause);
    }

    private static void show(Consumer<String> printFunc, String message) {
        printFunc.accept(message);
    }

    static List<Path> getJars(Path dir) {
        try {
            return Files.list(dir).filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar")).collect(Collectors.toList());
        } catch (IOException e) {
            throw showError("Unable to use jar-files from directory " + dir, e);
        }
    }

    private List<String> processNativeImageArgs(String[] args) {
        List<String> leftoverArgs = new ArrayList<>();
        Queue<String> arguments = new ArrayDeque<>();
        String defaultNativeImageArgs = getUserConfigProperties().get(pKeyNativeImageArgs);
        if (defaultNativeImageArgs != null && !defaultNativeImageArgs.isEmpty()) {
            arguments.addAll(Arrays.asList(defaultNativeImageArgs.split(" ")));
        }
        for (String arg : args) {

            switch (arg) {
                case "--language:all":
                    for (String lang : optionRegistry.getAvailableOptions(MacroOptionKind.Language)) {
                        arguments.add("--language:" + lang);
                    }
                    break;
                case "--tool:all":
                    for (String lang : optionRegistry.getAvailableOptions(MacroOptionKind.Tool)) {
                        arguments.add("--tool:" + lang);
                    }
                    break;
                default:
                    arguments.add(arg);
                    break;
            }
        }
        while (!arguments.isEmpty()) {
            boolean consumed = false;
            for (int index = optionHandlers.size() - 1; index >= 0; --index) {
                OptionHandler<? extends NativeImage> handler = optionHandlers.get(index);
                int numArgs = arguments.size();
                if (handler.consume(arguments)) {
                    assert arguments.size() < numArgs : "OptionHandler pretends to consume argument(s) but isn't: " + handler.getClass().getName();
                    consumed = true;
                    break;
                }
            }
            if (!consumed) {
                leftoverArgs.add(arguments.poll());
            }
        }
        return leftoverArgs;
    }

    protected String getXmsValue() {
        return "1g";
    }

    protected String getXmxValue(int maxInstances) {
        UnsignedWord memMax = PhysicalMemory.size().unsignedDivide(10).multiply(8).unsignedDivide(maxInstances);
        String maxXmx = "14g";
        if (memMax.aboveOrEqual(WordFactory.unsigned(SubstrateOptionsParser.parseLong(maxXmx)))) {
            return maxXmx;
        }
        return Long.toUnsignedString(memMax.rawValue());
    }

    static Map<String, String> loadProperties(Path propertiesPath) {
        Properties properties = new Properties();
        File propertiesFile = propertiesPath.toFile();
        if (propertiesFile.canRead()) {
            try (FileReader reader = new FileReader(propertiesFile)) {
                properties.load(reader);
            } catch (Exception e) {
                showError("Could not read properties-file: " + propertiesFile, e);
            }
        }
        Map<String, String> map = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            map.put(key, properties.getProperty(key));
        }
        return Collections.unmodifiableMap(map);
    }

    private static String deletedFileSuffix = ".deleted";

    protected static boolean isDeletedPath(Path toDelete) {
        return toDelete.getFileName().toString().endsWith(deletedFileSuffix);
    }

    protected void deleteAllFiles(Path toDelete) {
        try {
            Path deletedPath = toDelete;
            if (!isDeletedPath(deletedPath)) {
                deletedPath = toDelete.resolveSibling(toDelete.getFileName() + deletedFileSuffix);
                Files.move(toDelete, deletedPath);
            }
            Files.walk(deletedPath).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            if (isVerbose()) {
                showMessage("Could not recursively delete path: " + toDelete);
                e.printStackTrace();
            }
        }
    }
}
