/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.infra.operator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Registers, for reflection, every class of the Kubernetes models the operator reads and writes
 * through Jackson: fabric8's core, common and coordination models (ConfigMap, Secret, the Lease
 * leader election takes, status, watch events, metadata) and the Cutover resource itself.
 *
 * <p>Enumerated from the jars at build time rather than listed, for the reason the CLI's
 * reachability file gives for registering the whole admin model: a list derived from what one run
 * happened to touch is exactly as complete as that run, and a Kubernetes response carries types
 * nobody thought to exercise. A missing one fails at run time as a deserialisation error on the
 * API server's answer, which is the worst place to find out. Runs inside native-image only.
 */
public final class NativeImageFeature implements Feature {

    /** The model jars, by artifact name, whose every class is registered. */
    private static final List<String> MODELS = List.of("kubernetes-model-core-",
            "kubernetes-model-common-", "kubernetes-model-coordination-");

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (Path entry : access.getApplicationClassPath()) {
            String jar = entry.getFileName().toString();
            if (Files.isRegularFile(entry)
                    && MODELS.stream().anyMatch(model -> jar.startsWith(model))) {
                registerAll(access, entry, "io/fabric8/kubernetes/api/model/");
            }
        }
        for (Class<?> type : Cutover.class.getDeclaredClasses()) {
            register(type);
        }
        register(Cutover.class);
    }

    private static void registerAll(BeforeAnalysisAccess access, Path jar, String prefix) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".class")
                        && !name.endsWith("module-info.class")) {
                    Class<?> type = access.findClassByName(
                            name.substring(0, name.length() - 6).replace('/', '.'));
                    if (type != null) {
                        register(type);
                    }
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static void register(Class<?> type) {
        try {
            registerMembers(type);
        } catch (LinkageError optional) {
            // A model class whose signature names something not on the classpath: nothing the
            // operator can be sent, since Jackson could not build it on a JVM either.
        }
    }

    private static void registerMembers(Class<?> type) {
        RuntimeReflection.register(type);
        RuntimeReflection.registerAllDeclaredConstructors(type);
        RuntimeReflection.registerAllDeclaredMethods(type);
        RuntimeReflection.registerAllDeclaredFields(type);
        RuntimeReflection.register(type.getDeclaredConstructors());
        RuntimeReflection.register(type.getDeclaredMethods());
        RuntimeReflection.register(type.getDeclaredFields());
    }
}
