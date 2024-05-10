package net.raphimc.javadowngrader.gradle.task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.gradle.api.file.FileCopyDetails;

import net.lenni0451.classtransform.utils.tree.IClassProvider;

class FileCopyDetailsClassProvider implements IClassProvider {

    private final Map<String, ? extends FileCopyDetails> allFiles;
    private final Map<String, byte[]> rawData;

    public FileCopyDetailsClassProvider(Map<String, ? extends FileCopyDetails> allFiles, Map<String, byte[]> rawData) {
        this.allFiles = allFiles;
        this.rawData = rawData;
    }

    @Override
    @Nonnull
    public byte[] getClass(String name) throws ClassNotFoundException {
        byte[] file = this.rawData.get(name.replace('.', '/') + ".class");
        if (file == null) {
            throw new ClassNotFoundException("Class '" + name + "' not present in the backing collection of files.");
        }

        return file;
    }

    @Override
    @Nonnull
    public Map<String, Supplier<byte[]>> getAllClasses() {
        Map<String, Supplier<byte[]>> allClasses = new LinkedHashMap<>();

        for (String path : this.allFiles.keySet()) {
            if (!path.endsWith(".class") || path.contains("META-INF/versions/")) {
                continue;
            }

            String className = path.substring(0, path.length() - 6).replace('/', '.');
            allClasses.put(className, () -> {
                return this.rawData.get(path);
            });
        }

        return allClasses;
    }

}
