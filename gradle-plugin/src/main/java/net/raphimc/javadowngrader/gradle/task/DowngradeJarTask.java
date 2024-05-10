/*
 * This file is part of JavaDowngrader - https://github.com/RaphiMC/JavaDowngrader
 * Copyright (C) 2023-2024 RK_01/RaphiMC and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.javadowngrader.gradle.task;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.additionalclassprovider.LazyFileClassProvider;
import net.lenni0451.classtransform.additionalclassprovider.PathClassProvider;
import net.lenni0451.classtransform.utils.log.impl.SysoutLogger;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import net.raphimc.javadowngrader.impl.classtransform.JavaDowngraderTransformer;
import net.raphimc.javadowngrader.runtime.RuntimeRoot;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.WorkResults;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public abstract class DowngradeJarTask extends AbstractArchiveTask {
    @InputFiles
    public abstract ConfigurableFileCollection getCompileClassPath();

    @Input
    public abstract Property<Integer> getTargetVersion();

    @Input
    public abstract Property<Boolean> getCopyRuntimeClasses();

    public DowngradeJarTask() {
        getArchiveClassifier().convention("-downgraded");
        getArchiveExtension().convention("jar");
        getDestinationDirectory().set(getProject().getLayout().getBuildDirectory().dir("libs"));
        getTargetVersion().convention(Opcodes.V1_8);
        getCopyRuntimeClasses().convention(true);
    }

    @Override
    protected CopyAction createCopyAction() {
        return (CopyActionProcessingStream caps) -> {
            final Map<String, FileCopyDetailsInternal> fileDetails = new LinkedHashMap<>();
            final Map<String, byte[]> rawData = new HashMap<>();
            final Set<String> directories = new HashSet<>();
            final AtomicBoolean doneProcessing = new AtomicBoolean();

            caps.process((file) -> {
                if (doneProcessing.get()) {
                    throw new IllegalStateException("processFile called after the process call. This hints towards synchronization issues");
                }

                if (fileDetails.putIfAbsent(file.getRelativePath().getPathString(), file) != null) {
                    throw new IllegalStateException("Duplicate entry: '" + file.getRelativePath().getPathString() + "' (Note: duplicate file handling strategies are not yet implemented)");
                }

                if (!file.isDirectory()) {
                    long len = file.getSize();
                    ByteArrayOutputStream baos;
                    if (len <= 0 || len > (1 << 28)) { // We surmise that the provided length cannot be trusted here (1 << 28 = 256MiB)
                        baos = new ByteArrayOutputStream();
                    } else {
                        baos = new ByteArrayOutputStream((int) len);
                    }

                    file.copyTo(baos);
                    rawData.put(file.getRelativePath().getPathString(), baos.toByteArray());
                }

                RelativePath ownerDir = file.getRelativePath().getParent();
                while (ownerDir != null && directories.add(ownerDir.getPathString())) {
                    ownerDir = ownerDir.getParent();
                }
            });

            doneProcessing.set(true);

            final Collection<String> runtimeDeps = new LinkedHashSet<>();
            final TransformerManager transformerManager = new TransformerManager(
                    new FileCopyDetailsClassProvider(fileDetails, rawData)
            );
            transformerManager.addBytecodeTransformer(
                    JavaDowngraderTransformer.builder(transformerManager)
                            .targetVersion(getTargetVersion().get())
                            .depCollector(runtimeDeps::add)
                            .build()
            );

            try (OutputStream rawOut = Files.newOutputStream(this.getArchiveFile().get().getAsFile().toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    ZipOutputStream zipOut = new ZipOutputStream(rawOut)) {
                for (Map.Entry<String, FileCopyDetailsInternal> fileMapEntry : fileDetails.entrySet()) {
                    String path = fileMapEntry.getKey();
                    FileCopyDetailsInternal file = fileMapEntry.getValue();

                    if (file.isDirectory() && (file.isIncludeEmptyDirs() || directories.contains(path))) {
                        final ZipEntry entry = new ZipEntry(path + "/");
                        zipOut.putNextEntry(entry);
                        zipOut.closeEntry();
                    } else {
                        final ZipEntry entry = new ZipEntry(path);
                        zipOut.putNextEntry(entry);

                        if (!path.endsWith(".class") || path.contains("META-INF/versions/")) {
                            zipOut.write(rawData.get(path));
                            zipOut.closeEntry();
                            continue;
                        }

                        final byte[] result;
                        try {
                            final String className = path.substring(0, path.length() - 6).replace('/', '.');
                            result = transformerManager.transform(className, rawData.get(path));
                        } catch (Throwable e2) {
                            throw new RuntimeException("Failed to transform '" + path + "'", e2);
                        }

                        zipOut.write(result);
                        zipOut.closeEntry();
                    }
                }

                // Copy runtime classes
                if (getCopyRuntimeClasses().get()) {
                    for (final String runtimeDep : runtimeDeps) {
                        final String classPath = runtimeDep.concat(".class");
                        try (InputStream is = RuntimeRoot.class.getResourceAsStream("/" + classPath)) {
                            if (is == null) {
                                throw new IllegalStateException("Missing runtime class " + runtimeDep);
                            }

                            final Path dest = Paths.get(classPath);
                            Path directory = dest.getParent();
                            Queue<String> directoryQueue = Collections.asLifoQueue(new ArrayDeque<>());

                            while (directory != null && !directories.contains(directory.toString())) {
                                directoryQueue.add(directory.toString());
                                directories.add(directory.toString());
                                directory = directory.getParent();
                            }

                            while (!directoryQueue.isEmpty()) {
                                final ZipEntry entry = new ZipEntry(directoryQueue.poll() + "/");
                                zipOut.putNextEntry(entry);
                                zipOut.closeEntry();
                            }

                            zipOut.putNextEntry(new ZipEntry(classPath));
                            byte[] buffer = new byte[4096];
                            for (int read = is.read(buffer); read >= 0; read = is.read(buffer)) {
                                if (read != 0) {
                                    zipOut.write(buffer, 0, read);
                                }
                            }
                            zipOut.closeEntry();
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return WorkResults.didWork(true);
        };
    }
}
